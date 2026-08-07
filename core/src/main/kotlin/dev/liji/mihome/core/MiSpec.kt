package dev.liji.mihome.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- 线上 spec 的数据形状 ----------

@Serializable
data class SpecInstance(
    val type: String,
    val description: String = "",
    val services: List<SpecService> = emptyList(),
)

@Serializable
data class SpecService(
    val iid: Int,
    val type: String,
    val description: String = "",
    val properties: List<SpecProperty> = emptyList(),
    val actions: List<SpecAction> = emptyList(),
)

@Serializable
data class SpecProperty(
    val iid: Int,
    val type: String,
    val description: String = "",
    val format: String = "",
    val access: List<String> = emptyList(),
    val unit: String? = null,
    @SerialName("value-list") val valueList: List<SpecValue>? = null,
    @SerialName("value-range") val valueRange: List<Double>? = null,
) {
    val readable get() = "read" in access
    val writable get() = "write" in access
}

@Serializable
data class SpecValue(val value: Int, val description: String = "")

@Serializable
data class SpecAction(val iid: Int, val type: String, val description: String = "")

/** urn 形如 urn:miot-spec-v2:device:light:0000A001:linp-lx2bcw:1:0000C802，第 4 段是类别。 */
fun String.urnCategory(): String? = split(':').getOrNull(3)

// ---------- 归约成可渲染的控件 ----------

/**
 * 「日常会按」的属性类别白名单。按 spec 的属性 type 类别筛，不碰 piid——
 * 换个型号、换个固件都不用改。剩下的（默认上电状态、渐亮时长、雷达灵敏度…）是配置项，
 * 属于「在手机上设一次」的东西，不该占 33mm 圆屏。
 */
private val QUICK_WRITABLE = setOf(
    "on", "brightness", "color-temperature", "mode",
    "target-temperature", "fan-level", "vertical-swing", "horizontal-swing",
)

/** 值得在设备卡片上一眼看到的只读量。 */
private val QUICK_READONLY = setOf(
    "illumination", "occupancy-status", "electric-power",
    "temperature", "relative-humidity", "battery-level",
)

sealed interface Control {
    val siid: Int
    val label: String

    /** 是否属于主服务。非主服务的控件在小屏上默认收进「更多」。 */
    val primary: Boolean

    /** 是否属于日常控制集合。false 的进「设置」。 */
    val quick: Boolean

    data class Toggle(
        override val siid: Int,
        val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        /** 主服务里那个 `on` 属性——设备卡片上的主开关。 */
        val isPower: Boolean = false,
    ) : Control

    data class Range(
        override val siid: Int,
        val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        val min: Double,
        val max: Double,
        val step: Double,
        val unit: String?,
    ) : Control

    data class Choice(
        override val siid: Int,
        val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        val options: List<Pair<Int, String>>,
    ) : Control

    data class Readout(
        override val siid: Int,
        val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        val unit: String?,
    ) : Control
}

/** multiLanguage 的键是三位补零的：service:002:property:002:valuelist:000 */
class Translations(private val map: Map<String, String>) {
    fun service(siid: Int) = map["service:%03d".format(siid)]
    fun property(siid: Int, piid: Int) = map["service:%03d:property:%03d".format(siid, piid)]
    fun value(siid: Int, piid: Int, index: Int) =
        map["service:%03d:property:%03d:valuelist:%03d".format(siid, piid, index)]

    companion object {
        val EMPTY = Translations(emptyMap())
    }
}

/**
 * 只保留日常会用到的控件。两条规则解决了两个真实存在的坑：
 *
 * 1. 主服务规则——电源开关只认「服务类别 == 设备类别」的那个服务。
 *    桌灯 xiaomi.light.lamp35 的 siid 6 是 focus-mode（番茄钟），它也有个 `on` 属性；
 *    按「谁叫 on 谁就是电源」会把台灯开关接到番茄钟上。
 * 2. 不硬编码 piid——桌灯没有色温，spec 里就没有 color-temperature，控件自然不生成。
 */
fun SpecInstance.toControls(
    t: Translations = Translations.EMPTY,
    includeReadouts: Boolean = true,
): List<Control> {
    val deviceCat = type.urnCategory()
    val primarySiid = services.firstOrNull { it.type.urnCategory() == deviceCat }?.iid

    val out = mutableListOf<Control>()
    for (svc in services) {
        if (svc.type.urnCategory() == "device-information") continue
        val isPrimary = svc.iid == primarySiid
        val svcLabel = t.service(svc.iid) ?: svc.description

        for (p in svc.properties) {
            val cat = p.type.urnCategory()
            val label = t.property(svc.iid, p.iid) ?: p.description.ifEmpty { cat.orEmpty() }
            val full = if (isPrimary) label else "$svcLabel · $label"

            // `on` 只在主服务里算常用：桌灯 siid 6 的 focus-mode 也有个 `on`，
            // 只按类别筛会让表上多出一个「开关」，按下去其实是启动番茄钟。
            // 其余类别（fan-level、vertical-swing 等）在非主服务里也照常算常用——
            // 空调的风机控制就在 siid 3。
            val quickW = cat in QUICK_WRITABLE && (cat != "on" || isPrimary)
            val quickR = cat in QUICK_READONLY

            when {
                p.writable && p.format == "bool" ->
                    out += Control.Toggle(svc.iid, p.iid, full, isPrimary, quickW, isPower = isPrimary && cat == "on")

                p.writable && p.valueList != null ->
                    out += Control.Choice(
                        svc.iid, p.iid, full, isPrimary, quickW,
                        p.valueList.mapIndexed { i, v -> v.value to (t.value(svc.iid, p.iid, i) ?: v.description) },
                    )

                p.writable && p.valueRange != null && p.valueRange.size >= 3 ->
                    out += Control.Range(
                        svc.iid, p.iid, full, isPrimary, quickW,
                        p.valueRange[0], p.valueRange[1], p.valueRange[2], p.unit,
                    )

                includeReadouts && p.readable && !p.writable && p.format != "string" ->
                    out += Control.Readout(svc.iid, p.iid, full, isPrimary, quickR, p.unit)
            }
        }
    }
    // 常用优先，其次主服务，电源开关排最前
    return out.sortedWith(
        compareByDescending<Control> { it.quick }
            .thenByDescending { it.primary }
            .thenByDescending { it is Control.Toggle && it.isPower },
    )
}

// ---------- 拉取与缓存 ----------

/**
 * spec 服务器免鉴权，所以设备建模可以完全脱离登录来调试。
 * 缓存 key 直接用 urn 原文：urn 自带版本后缀，固件升级会产生新 urn＝新 key，
 * 失效逻辑是免费的，不需要 TTL。
 */
class SpecClient(private val cacheDir: java.io.File? = null) {

    private val client = MiHttp.client(readTimeoutSec = 60, callTimeoutSec = 90)
    private val mem = HashMap<String, SpecInstance>()

    fun spec(urn: String): SpecInstance = mem.getOrPut(urn) {
        miJson.decodeFromString(cached("$urn.spec") { MiHttp.getText(client, "${SPEC_BASE}miot-spec-v2/instance?type=$urn") })
    }

    fun translations(urn: String, lang: String = "zh_cn"): Translations {
        val body = runCatching {
            cached("$urn.i18n") { MiHttp.getText(client, "${SPEC_BASE}instance/v2/multiLanguage?urn=$urn") }
        }.getOrNull() ?: return Translations.EMPTY
        // 端点在找不到 model 时返回纯文本而不是 JSON
        if (!body.trimStart().startsWith("{")) return Translations.EMPTY
        val root = runCatching { miJson.parseToJsonElement(body) }.getOrNull() ?: return Translations.EMPTY
        val data = (root as? kotlinx.serialization.json.JsonObject)?.get("data")
            as? kotlinx.serialization.json.JsonObject ?: return Translations.EMPTY
        val node = data[lang] as? kotlinx.serialization.json.JsonObject ?: return Translations.EMPTY
        return Translations(
            node.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() },
        )
    }

    private fun cached(key: String, fetch: () -> String): String {
        val f = cacheDir?.resolve(sha1(key) + ".json")
        if (f != null && f.isFile) return f.readText()
        val body = fetch()
        if (f != null) {
            f.parentFile?.mkdirs()
            f.writeText(body)
        }
        return body
    }

    private fun sha1(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
