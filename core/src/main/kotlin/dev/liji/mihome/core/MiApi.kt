package dev.liji.mihome.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
)

class MiApi(private val store: Store, private val auth: MiAuth, var verbose: Boolean = false) {

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

        val req = MiHttp.req(MIOT_API_BASE + p)
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
