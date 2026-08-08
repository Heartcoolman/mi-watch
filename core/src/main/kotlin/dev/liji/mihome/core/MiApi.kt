package dev.liji.mihome.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody

data class PropRef(val did: String, val siid: Int, val piid: Int)

data class PropValue(
    val did: String,
    val siid: Int,
    val piid: Int,
    val value: JsonElement?,
    /** 逐项状态码。参考实现从不读它，于是设备离线时会把陈旧值当真值渲染。非 0 应显示为「—」。 */
    val code: Int,
) {
    private val prim get() = value as? JsonPrimitive

    /** code 非 0 一律当无效值，不让陈旧数据看起来像可用状态。 */
    val asBool: Boolean? get() = if (code != 0) null else prim?.booleanOrNull
    val asInt: Int? get() = if (code != 0) null else prim?.intOrNull
    val asDouble: Double? get() = if (code != 0) null else prim?.doubleOrNull
    val asText: String? get() = if (code != 0) null else prim?.contentOrNull
}

/** 类型化的设备信息，好让 :wear 完全不必依赖 kotlinx-serialization。 */
data class DeviceInfo(
    val did: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val specType: String?,
    /** 非空表示是网关下挂的子设备。 */
    val parentId: String?,
    /** 米家自己记的使用次数。用来给「首次启动自动收藏」排序——比按名字字母序靠谱得多。 */
    val cnt: Int = 0,
) {
    /**
     * BLE 设备的 did 形如 blt.3.xxx，经网关中转。
     * v1 曾整体排除，v2 实测**云端 prop/get 对它们照常返回真值**（8/8 全通），
     * 所以不再排除；这个标记只留给需要区分链路的地方。
     */
    val isBle get() = did.startsWith("blt.")
}

/**
 * 一个手动场景。
 *
 * 只有手动场景进得来：自动化（传感器/定时触发）在表上点一下毫无意义，而它们在数量上
 * 是压倒性的——实测账号 13 个场景里只有 2 个是手动的。
 */
data class SceneInfo(
    val id: String,
    val name: String,
    val homeId: Long,
    /** 米家自己的「常用」标记，用来排序——它比创建时间更接近用户的实际偏好。 */
    val commonUse: Boolean = false,
)

data class HomeInfo(
    val id: Long,
    val uid: Long,
    val name: String,
    /** did → 房间名。归属是反的：房间对象上列 dids，设备记录里没有房间字段。 */
    val rooms: Map<String, String> = emptyMap(),
)

class MiApi(private val store: Store, private val auth: MiAuth, var verbose: Boolean = false) {

    private companion object {
        const val KEY_REGION = "region"
    }

    /** 不挂 cookie jar：签名请求的 Cookie 头是手工拼的，jar 会因域名不匹配而干扰。 */
    private val client = MiHttp.client()
    private val refreshLock = Any()

    fun post(path: String, body: String): JsonObject {
        val s = store.loadSession() ?: throw MiException("未登录：先跑 login-qr")
        val first = runCatching { call(path, body, s) }
        first.getOrNull()?.let { if (it.code() == 0) return it }
        if (verbose) System.err.println("[api] $path 首次失败(${first.exceptionOrNull()?.message ?: "code=" + first.getOrNull()?.code()})，刷新令牌重试")

        // 刷新加锁：两个并发调用各自刷新会互相覆盖 ssecurity，之后所有签名失败且无线索。
        val fresh = synchronized(refreshLock) { auth.refresh(store.loadSession() ?: throw MiException("未登录")) }
        val second = call(path, body, fresh)
        if (second.code() != 0) {
            throw MiException("接口 $path 返回 code=${second.code()} ${second["message"]?.toString().orEmpty()}")
        }
        return second
    }

    private fun call(path: String, body: String, s: Session): JsonObject {
        val p = path.trimStart('/')
        val nonce = MiCrypto.randomNonce()
        val signedNonce = MiCrypto.signedNonce(s.ssecurity, nonce)
        // data 必须在「签名输入」和「发出去的内容」之间逐字节相同：只序列化一次，两处都用它。
        val signature = MiCrypto.sign("/$p", signedNonce, nonce, body)

        val form = FormBody.Builder()
            .add("_nonce", nonce)
            .add("data", body)
            .add("signature", signature)
            .build()

        val req = MiHttp.req(miotApiBase(store.get(KEY_REGION)) + p)
            .post(form)
            .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2") // 拼写错误是原样必需的
            .header("Cookie", "PassportDeviceId=${s.deviceId};userId=${s.userId};serviceToken=${s.serviceToken}")
            .build()

        if (verbose) System.err.println("[api] POST /$p  data=$body")
        val text = client.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw MiException("POST /$p -> HTTP ${r.code}: ${t.take(300)}")
            t
        }
        if (verbose) System.err.println("[api] <- ${text.take(600)}")
        return miJson.parseToJsonElement(text).jsonObject
    }

    fun profile(): JsonObject {
        val s = store.loadSession() ?: throw MiException("未登录")
        return post("home/profile", bodyOf { put("id", s.userId) })
    }

    fun getHomes(): JsonObject = post(
        "v2/homeroom/gethome",
        bodyOf {
            put("app_ver", 7)
            put("fetch_share", true)
            put("fetch_share_dev", true)
            put("fg", false)
            put("limit", 300)
        },
    )

    fun getDevices(homeOwnerUid: Long, homeId: Long): JsonObject = post(
        "v2/home/home_device_list",
        bodyOf {
            put("home_owner", homeOwnerUid)
            put("home_id", homeId)
            put("limit", 200)
        },
    )

    fun propGet(items: List<PropRef>): List<PropValue> {
        val env = post(
            "miotspec/prop/get",
            bodyOf {
                putJsonArray("params") {
                    items.forEach { addJsonObject { put("did", it.did); put("siid", it.siid); put("piid", it.piid) } }
                }
            },
        )
        return (env["result"] as? JsonArray).orEmpty().map { e ->
            val o = e.jsonObject
            PropValue(
                did = o["did"]?.jsonPrimitive?.content.orEmpty(),
                siid = o["siid"]?.jsonPrimitive?.intOrNull ?: -1,
                piid = o["piid"]?.jsonPrimitive?.intOrNull ?: -1,
                value = o["value"],
                code = o["code"]?.jsonPrimitive?.intOrNull ?: -1,
            )
        }
    }

    /** 类型化封装，:wear 只用这几个，不必接触 JSON。 */
    /**
     * 全部家庭，连房间表一起返回。
     *
     * 必须是「全部」而不是「第一个」：一个账号有多套房产是常态（本机测试账号就有
     * 我的家 / 奶奶家 / 姥姥家三个），只读第一个会让另外两处的设备**在 App 里完全不存在**。
     * 房间表顺路一起解析，省掉一次 gethome 往返。
     */
    fun homes(): List<HomeInfo> =
        (getHomes()["result"]?.jsonObject?.get("homelist") as? JsonArray).orEmpty().map { e ->
            val o = e.jsonObject
            val rooms = HashMap<String, String>()
            (o["roomlist"] as? JsonArray).orEmpty().forEach { r ->
                val name = r.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                (r.jsonObject["dids"] as? JsonArray).orEmpty().forEach { d ->
                    d.jsonPrimitive.contentOrNull?.let { rooms[it] = name }
                }
            }
            HomeInfo(
                id = o["id"]!!.jsonPrimitive.content.toLong(),
                uid = o["uid"]!!.jsonPrimitive.longOrNull ?: error("家庭缺 uid"),
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                rooms = rooms,
            )
        }

    /**
     * 探测账号归属的区域，返回带家庭的那个。登录后只跑一次，结果落盘。
     * 判据是「能返回非空家庭列表」——错误区域会返回成功但空的结果，
     * 只看 HTTP 状态或 code 分不出来。
     */
    fun detectRegion(): String? {
        for (r in MIOT_REGIONS) {
            store.set(KEY_REGION, r)
            val homes = runCatching { homes() }.getOrDefault(emptyList())
            if (homes.isNotEmpty()) return r
        }
        // 一个都没成的话把标记清掉，让下次启动重试——可能只是这次网络不好，
        // 记成 cn 会让海外账号永远停在空列表上，且没有任何线索
        store.set(KEY_REGION, null)
        return null
    }

    fun devices(homeOwnerUid: Long, homeId: Long): List<DeviceInfo> =
        (getDevices(homeOwnerUid, homeId)["result"]?.jsonObject?.get("device_info") as? JsonArray)
            .orEmpty().map { e ->
                val o = e.jsonObject
                DeviceInfo(
                    did = o["did"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    model = o["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    online = o["isOnline"]?.jsonPrimitive?.booleanOrNull ?: false,
                    specType = o["spec_type"]?.jsonPrimitive?.contentOrNull,
                    parentId = o["parent_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                    cnt = o["cnt"]?.jsonPrimitive?.intOrNull ?: 0,
                )
            }

    /**
     * 一个家庭的手动场景。
     *
     * 路径是四段的 gRPC-gateway 风格，`AppSceneService` 那一段不能省——公开资料里流传的
     * `appgateway/miot/appsceneservice/AppGetHomeSceneList`、`AppGetSceneList`、`AppSceneRun`
     * 一律 404；老的 `scene/list` / `v2/scene/list` 虽然还在（返回 code=0），但无论传什么参数
     * 都恒返回空对象，是废弃端点。这三族全试过，只有这一条能拿到数据。
     *
     * home_id 必须是数字：这里跟 home_device_list 一致，而 gethome 返回的是字符串。
     * 没有场景的家庭返回 `result: null`（不是空数组），所以 as? 之后要能落回空列表。
     */
    fun scenesOf(homeId: Long): List<SceneInfo> {
        val res = post(
            "appgateway/miot/appsceneservice/AppSceneService/GetSceneList",
            bodyOf { put("home_id", homeId) },
        )["result"] as? JsonObject ?: return emptyList()

        return (res["scene_info_list"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            // 手动场景的判据是触发器来源为 user（key 为 user.click）。
            // 不用记录里的 `type` 字段——它对手动和自动化都是 0，分不出来。
            val manual = (o["scene_trigger"]?.jsonObject?.get("triggers") as? JsonArray).orEmpty()
                .any { it.jsonObject["src"]?.jsonPrimitive?.contentOrNull == "user" }
            // 停用的场景在米家 App 里也是隐藏的，跟着藏
            val enabled = o["enable"]?.jsonPrimitive?.booleanOrNull ?: true
            if (!manual || !enabled) return@mapNotNull null
            SceneInfo(
                id = o["scene_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                homeId = homeId,
                commonUse = o["common_use"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }

    /** 全部家庭的手动场景。和设备一样：只读第一个家庭会让其余房产的场景完全不存在。 */
    fun scenes(): List<SceneInfo> = homes().flatMap { scenesOf(it.id) }

    /**
     * 执行一个手动场景。
     *
     * 方法名必须是 `NewRunScene`。同一个服务下的 `RunScene` 也存在、也返回
     * `code=0 result=true`，**但设备纹丝不动**——实测跑完连读三次，目标灯的回读全是旧值。
     * 照着那个返回值写代码，得到的是一个「点了没反应却报成功」的功能。
     *
     * `scene_type` 必填且为 2：漏掉报 `code=-8 invaild scene_type`，填 1 报 HTTP 500
     * `sceneInfo is empty`，填 3 又回到 -8。它和场景记录里那个恒为 0 的 `type` 字段无关。
     */
    fun runScene(id: String): Boolean = post(
        "appgateway/miot/appsceneservice/AppSceneService/NewRunScene",
        bodyOf {
            put("scene_id", id)
            put("scene_type", 2)
            put("trigger_key", "user.click")
        },
    ).code() == 0

    fun setBool(did: String, siid: Int, piid: Int, value: Boolean): Boolean =
        propSet(listOf(PropRef(did, siid, piid) to JsonPrimitive(value))).code() == 0

    fun setInt(did: String, siid: Int, piid: Int, value: Int): Boolean =
        propSet(listOf(PropRef(did, siid, piid) to JsonPrimitive(value))).code() == 0

    /**
     * 数值属性。[integral] 为真时按整数发——像空调设定温度这种 format 是 float
     * 但步进为 1 的属性，发 26 比发 26.0 更贴近米家 App 自己的行为。
     */
    fun setNumber(did: String, siid: Int, piid: Int, value: Double, integral: Boolean): Boolean =
        propSet(
            listOf(
                PropRef(did, siid, piid) to
                    if (integral) JsonPrimitive(value.toLong()) else JsonPrimitive(value),
            ),
        ).code() == 0

    /** 触发一个无入参动作。返回是否成功——:wear 不接触 JSON，所以这里就把信封拆掉。 */
    fun invokeAction(did: String, siid: Int, aiid: Int): Boolean =
        action(did, siid, aiid).code() == 0

    /** 带一个入参的动作。单入参时 in 就是 [值]，顺序问题不存在。 */
    fun invokeAction(did: String, siid: Int, aiid: Int, arg: Int): Boolean =
        action(did, siid, aiid, listOf(JsonPrimitive(arg))).code() == 0

    fun propSet(items: List<Pair<PropRef, JsonElement>>): JsonObject = post(
        "miotspec/prop/set",
        bodyOf {
            putJsonArray("params") {
                items.forEach { (ref, v) ->
                    addJsonObject {
                        put("did", ref.did); put("siid", ref.siid); put("piid", ref.piid); put("value", v)
                    }
                }
            }
        },
    )

    /** 注意：action 的 params 是**单个对象**，与 prop/get、prop/set 的数组形状不同。 */
    fun action(did: String, siid: Int, aiid: Int, args: List<JsonElement> = emptyList()): JsonObject = post(
        "miotspec/action",
        bodyOf {
            putJsonObject("params") {
                put("did", did); put("siid", siid); put("aiid", aiid)
                putJsonArray("in") { args.forEach { add(it) } }
            }
        },
    )

    private fun bodyOf(build: JsonObjectBuilder.() -> Unit): String =
        miJson.encodeToString(JsonObject.serializer(), buildJsonObject(build))
}

fun JsonObject.code(): Int = this["code"]?.jsonPrimitive?.intOrNull ?: -1

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
