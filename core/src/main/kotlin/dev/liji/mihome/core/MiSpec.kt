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
    "target-temperature", "target-humidity", "fan-level", "speed-level", "wind-speed",
    "vertical-swing", "horizontal-swing",
    "volume", "mute",
)

/**
 * 允许在非主服务里出现电源开关的服务类别。
 *
 * `on` 默认只认主服务（桌灯 siid6 是番茄钟，它也有 `on`，见下面 toControls 的注释）。
 * 但油烟机的照明、摄像机的白光灯确实是独立的、天天要按的开关，它们各自挂在
 * light / white-light 服务下。按**服务类别**放行比按属性名白名单准得多——
 * 试过用 `light-on`，结果抓到的是油烟机「蓝牙联动指示灯」而不是照明。
 */
private val SUB_POWER_SERVICES = setOf("light", "white-light", "fan")

/**
 * 只读量不像可写属性那样有误触风险，所以规则反过来：**主服务里的只读量默认全要**，
 * 只挡掉明显是统计/诊断的；另加一小撮无论在哪个服务里出现都值得看的量。
 *
 * 为什么不继续用白名单：v1 那 6 个类别只够覆盖灯和空调。真跑一遍 21 个设备就会发现
 * 燃气报警器（status/gas-concentration）、路由器（download-speed/连接数）、
 * 体脂秤、音箱的常用控件数全是 0——白名单每加一种设备就要改一次，正是要避免的东西。
 */
private val ALWAYS_READ = setOf(
    "temperature", "relative-humidity", "battery-level", "illumination",
    "electric-power", "occupancy-status", "playing-state",
)

/** 主服务里也不该占 33mm 屏的：累计统计、固件版本、倒计时残值、事件流水号。 */
private val READ_NOISE = setOf(
    "fault", "working-time", "light-on-times", "remote-num", "radar-ver",
    "has-someone-duration", "no-one-duration", "delay-remain-time", "air-dry-remain-time",
    "connect-device-ids", "device-status", "online-timestamp", "audio-id",
    "current-time", "operation-id", "ptz-camera", "camera-count",
    "storage-total-space", "storage-free-space", "storage-used-space",
    "video-codec", "audio-codec", "audio-bit-width", "audio-smp-rate", "audio-channel",
    "self-clean", "clean-left-time", "filter-clean", "dry-cleaning-status",
    "dry-cleaning-guide", "dry-cleaning-left-time", "customized-property-1",
)

sealed interface Control {
    val siid: Int
    val piid: Int
    val label: String

    /** 是否属于主服务。非主服务的控件在小屏上默认收进「更多」。 */
    val primary: Boolean

    /** 是否属于日常控制集合。false 的进「设置」。 */
    val quick: Boolean

    data class Toggle(
        override val siid: Int,
        override val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        /** 主服务里那个 `on` 属性——设备卡片上的主开关。 */
        val isPower: Boolean = false,
    ) : Control

    data class Range(
        override val siid: Int,
        override val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        val min: Double,
        val max: Double,
        val step: Double,
        val unit: String?,
    ) : Control {
        /** 步进为整数时按整数发值，避免 26.0 这种写法。 */
        val integral get() = step % 1.0 == 0.0 && min % 1.0 == 0.0

        /** 色温这类跨度极大的量，在 33mm 圆屏上连续滑动没法用，UI 会改渲染成几个预设档。 */
        val isKelvin get() = unit == "kelvin"

        fun clamp(v: Double): Double = v.coerceIn(min, max)

        fun stepped(v: Double): Double = clamp(min + Math.round((v - min) / step) * step)
    }

    data class Choice(
        override val siid: Int,
        override val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        val options: List<Pair<Int, String>>,
    ) : Control

    data class Readout(
        override val siid: Int,
        override val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        val unit: String?,
        /** 有枚举的只读量（燃气 status、有人无人…）靠它把数字译回人话。 */
        val options: List<Pair<Int, String>> = emptyList(),
        /** 属性类别（occupancy-status、download-speed…），UI 靠它决定怎么格式化。 */
        val cat: String? = null,
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
    // 有些设备的主服务名和设备类别对不上：摄像机的顶层类别是 camera，服务却叫 camera-control。
    // 找不到同名服务时退到第一个非 device-information 的服务，否则整台设备一个控件都出不来。
    val primarySiid = services.firstOrNull { it.type.urnCategory() == deviceCat }?.iid
        ?: services.firstOrNull { it.type.urnCategory() != "device-information" }?.iid

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
            val quickW = cat in QUICK_WRITABLE &&
                (cat != "on" || isPrimary || svc.type.urnCategory() in SUB_POWER_SERVICES)
            val quickR = cat in ALWAYS_READ || (isPrimary && cat != null && cat !in READ_NOISE)

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
                    out += Control.Readout(
                        svc.iid, p.iid, full, isPrimary, quickR, p.unit,
                        p.valueList.orEmpty()
                            .mapIndexed { i, v -> v.value to (t.value(svc.iid, p.iid, i) ?: v.description) },
                        cat,
                    )
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
