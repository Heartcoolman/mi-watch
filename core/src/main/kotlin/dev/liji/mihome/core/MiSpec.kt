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
) {
    /** 有没有真能读、能写或能触发的成员。access 为空的属性只是事件载荷描述。 */
    val usable: Boolean
        get() = properties.any { it.readable || it.writable } || actions.any { it.inputs.isEmpty() }
}

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
data class SpecAction(
    val iid: Int,
    val type: String,
    val description: String = "",
    /** 入参的 piid 列表。非空表示这个动作要先填值，表上不做。 */
    @SerialName("in") val inputs: List<Int> = emptyList(),
)

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
 * 主服务里也不该出现在表上的可写项：上电默认值、渐变时长、灵敏度、校准、童锁……
 * 这些是「在手机上设一次」的配置，不是日常会按的东西。
 */
private val WRITE_NOISE = setOf(
    "default-power-on-state", "light-on-gradient-time", "light-off-gradient-time",
    "physical-controls-locked", "sensitivity", "detection-sensitivity", "calibration",
    "power-frequency", "image-rollover", "image-distortion-correction", "time-watermark",
    "off-delay-time", "off-delay", "countdown-time", "delay", "delay-time",
    "alarm", "alarm-interval", "enable-time-period", "no-disturb", "sleep-mode",
    "motion-detection-start-time", "motion-detection-end-time", "set-filter-level",
    "match-state", "ac-work-mode", "ac-state", "eng-mode", "current-time",
)

/** 这些服务整个的动作都算常用——遥控类设备的功能就是这几个按钮。 */
private val ACTION_SERVICES = setOf(
    "ir-aircondition-control", "ir-tv-control", "ir-fan-control", "ir-stb-control",
    "ir-box-control", "ir-projector-control", "remote-control", "play-control",
    "vacuum", "battery",
)

/** 排在最前的动作：它们是这台设备的「电源」。 */
private val POWER_ACTIONS = setOf("turn-on", "turn-off", "toggle", "start-sweep", "stop-sweeping", "play", "pause")

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
    // 传感器的「那个值」——它在哪个服务里不重要，它就是这台设备存在的理由
    "contact-state", "door-state", "door-status", "motion-state", "submersion-state",
    "smoke-concentration", "gas-concentration", "pm2.5-density", "pm10-density",
    "co2-density", "tvoc-density", "water-level", "weight", "status",
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
    val label: String

    /** 是否属于主服务。非主服务的控件在小屏上默认收进「更多」。 */
    val primary: Boolean

    /** 是否属于日常控制集合。false 的进「设置」。 */
    val quick: Boolean

    /**
     * 排序权重，0 最靠前。
     *
     * v1 把白名单当门禁用：不在名单里就不显示。拿 457 个真实型号跑一遍才发现这不成立——
     * **20% 的设备零控件**，连窗帘（motor-control / target-position）这种核心品类都空着。
     * 白名单永远追不上 177 个品类。
     *
     * 改成排序：白名单命中的排 rank 0，主服务里的其他可写项排 rank 1，两者都算「常用」。
     * 该显示的一个不少，重要的仍然排在前面、不用滚就能看见。
     */
    val rank: Int

    /** 有 piid 的控件。Act 没有 piid，所以单独分一层。 */
    sealed interface Prop : Control {
        val piid: Int
    }

    data class Toggle(
        override val siid: Int,
        override val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        override val rank: Int = 1,
        /** 主服务里那个 `on` 属性——设备卡片上的主开关。 */
        val isPower: Boolean = false,
    ) : Prop

    data class Range(
        override val siid: Int,
        override val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        override val rank: Int = 1,
        val min: Double,
        val max: Double,
        val step: Double,
        val unit: String?,
    ) : Prop {
        /** 步进为整数时按整数发值，避免 26.0 这种写法。 */
        val integral get() = step % 1.0 == 0.0 && min % 1.0 == 0.0

        /** 色温这类跨度极大的量，在 33mm 圆屏上连续滑动没法用，UI 会改渲染成几个预设档。 */
        val isKelvin get() = unit == "kelvin"

        fun clamp(v: Double): Double = v.coerceIn(min, max)

        fun stepped(v: Double): Double = clamp(min + Math.round((v - min) / step) * step)

        /**
         * 弹层选择用的档位。色温给 4 个标准档（2700 到 6500 连续滑没有可用精度），
         * 其余整数范围枚举或采样。曾经的 bug：非色温 Range 的 chip 点开弹层，
         * 选项按色温档过滤后是空的——整层白板。
         */
        fun presets(): List<Pair<Int, String>> = rangeOptions(min, max, step, unit)
    }

    data class Choice(
        override val siid: Int,
        override val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        override val rank: Int = 1,
        val options: List<Pair<Int, String>>,
    ) : Prop

    data class Readout(
        override val siid: Int,
        override val piid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        override val rank: Int = 1,
        val unit: String?,
        /** 有枚举的只读量（燃气 status、有人无人…）靠它把数字译回人话。 */
        val options: List<Pair<Int, String>> = emptyList(),
        /** 属性类别（occupancy-status、download-speed…），UI 靠它决定怎么格式化。 */
        val cat: String? = null,
    ) : Prop

    /** 单入参动作的入参描述：从这些档里选一个作为唯一入参发出去。 */
    data class ActArg(val piid: Int, val options: List<Pair<Int, String>>)

    /**
     * 动作。无入参的点一下就发；单入参的先弹层选值再发。
     *
     * 加它是为了红外伪设备：万能遥控器模拟出来的空调/电视/风扇，属性要么只写要么为空，
     * **全部功能都挂在 action 上**——不支持 action 就等于这些设备在表上完全是空的，
     * 它们在 457 个型号的扫描里占了「零控件」的一大半。扫地机的 start-sweep、
     * 投影仪的方向键也是同一类。
     *
     * 单入参只收「入参属性带枚举或整数范围约束」的：表上的交互是从列表里选一个，
     * 自由输入（字符串、浮点）在 33mm 圆屏上没有可用的输入法。多入参不做——
     * 连填几个值的链路太长，收益不抵复杂度。
     */
    data class Act(
        override val siid: Int,
        val aiid: Int,
        override val label: String,
        override val primary: Boolean,
        override val quick: Boolean,
        override val rank: Int = 1,
        /** null＝无入参。非空时点击应先弹选择层。 */
        val arg: ActArg? = null,
    ) : Control
}

/**
 * 把整数范围枚举成可选档。步数少就全列（扫地机吸力 1..4），
 * 多则等距取 8 档再按步进取整（音量 0..100）。非整数范围返回空——不可枚举。
 */
internal fun rangeOptions(min: Double, max: Double, step: Double, unit: String?): List<Pair<Int, String>> {
    if (step <= 0 || max < min) return emptyList()
    if (step % 1.0 != 0.0 || min % 1.0 != 0.0) return emptyList()
    // 色温优先标准 4 档：等距取出来的 3243K 没人认识。但量程覆盖不到任何标准档时
    // （2000–2600K 的暖光灯带）要落回普通枚举——返回空会让弹层变成一块白板
    if (unit == "kelvin") {
        val std = listOf(2700, 3500, 5000, 6500).filter { it >= min && it <= max }
        if (std.isNotEmpty()) return std.map { it to "${it}K" }
    }
    val count = ((max - min) / step).toInt() + 1
    // 上限 16 是为了让空调 16–30° 这样的量程逐度全列——弹层可以滚动，
    // 全列比采样好用；音量 0–100 这种才需要采样
    val values = if (count <= 16) {
        (0 until count).map { (min + it * step).toInt() }
    } else {
        // 网格内的最大可取值。量程不是步进的整倍数时，四舍五入会把最后一档
        // 顶出 max（0–101 步 3 → 102），发出去被云端拒
        val maxOnGrid = min + Math.floor((max - min) / step) * step
        (0 until 8).map { i ->
            val raw = min + (max - min) * i / 7
            (min + Math.round((raw - min) / step) * step).coerceAtMost(maxOnGrid).toInt()
        }.distinct()
    }
    return values.map { it to (trimNum(it.toDouble()) + shortUnit(unit)) }
}

/** multiLanguage 的键是三位补零的：service:002:property:002:valuelist:000 */
class Translations(private val map: Map<String, String>) {
    fun service(siid: Int) = map["service:%03d".format(siid)]
    fun property(siid: Int, piid: Int) = map["service:%03d:property:%03d".format(siid, piid)]
    fun value(siid: Int, piid: Int, index: Int) =
        map["service:%03d:property:%03d:valuelist:%03d".format(siid, piid, index)]

    fun action(siid: Int, aiid: Int) = map["service:%03d:action:%03d".format(siid, aiid)]

    companion object {
        val EMPTY = Translations(emptyMap())
    }
}

/**
 * 把 spec 归约成表上能渲染的控件集合。
 *
 * 四条规则，每条都对应一个用真实语料测出来的失败：
 *
 * 1. **主服务规则**——电源开关只认「服务类别 == 设备类别」的那个服务。桌灯
 *    xiaomi.light.lamp35 的 siid 6 是 focus-mode（番茄钟），它也有个 `on` 属性；
 *    按「谁叫 on 谁就是电源」会把台灯开关接到番茄钟上。
 * 2. **主服务回退**——摄像机的顶层类别是 camera，服务却叫 camera-control，同名匹配
 *    直接落空、整台设备零控件。找不到就退到第一个非 device-information 的服务。
 * 3. **白名单只排序、不拦截**——见 Control.rank。拦截会让 20% 的型号变成空卡片。
 * 4. **收 action**——红外伪设备的功能全在 action 上，不收就等于不支持它们。
 *
 * 不硬编码 piid：桌灯没有色温，spec 里就没有 color-temperature，控件自然不生成。
 */
fun SpecInstance.toControls(
    t: Translations = Translations.EMPTY,
    includeReadouts: Boolean = true,
): List<Control> {
    val deviceCat = type.urnCategory()
    // 回退不能只看「不是 device-information」：门磁 chuangmi.door.515a01 的第一个服务
    // 全是 access 为空的事件描述属性，挑中它等于挑了个死胡同，整台设备零控件。
    // 要挑第一个**真的有可用成员**的服务。
    val primarySiid = services.firstOrNull { it.type.urnCategory() == deviceCat }?.iid
        ?: services.firstOrNull { it.type.urnCategory() != "device-information" && it.usable }?.iid
        ?: services.firstOrNull { it.type.urnCategory() != "device-information" }?.iid

    val out = mutableListOf<Control>()
    for (svc in services) {
        val svcCat = svc.type.urnCategory()
        if (svcCat == "device-information") continue
        val isPrimary = svc.iid == primarySiid
        val svcLabel = t.service(svc.iid) ?: svc.description

        for (p in svc.properties) {
            val cat = p.type.urnCategory()
            val label = t.property(svc.iid, p.iid) ?: p.description.ifEmpty { cat.orEmpty() }
            val full = if (isPrimary) label else "$svcLabel · $label"

            // `on` 只在主服务、或 light/fan 这类子设备服务里才算电源：桌灯 siid6 的
            // focus-mode 也有 `on`，按下去其实是启动番茄钟；而油烟机的照明、
            // 摄像机的白光灯确实是独立开关，值得单列。
            val onOk = cat != "on" || isPrimary || svcCat in SUB_POWER_SERVICES
            val write0 = cat in QUICK_WRITABLE && onOk
            val write1 = isPrimary && cat != null && cat !in WRITE_NOISE && onOk
            val read0 = cat in ALWAYS_READ
            val read1 = isPrimary && cat != null && cat !in READ_NOISE

            val quickW = write0 || write1
            val quickR = read0 || read1
            val rankW = if (write0) 0 else 1
            val rankR = if (read0) 0 else 1

            when {
                p.writable && p.format == "bool" ->
                    out += Control.Toggle(
                        svc.iid, p.iid, full, isPrimary, quickW, rankW,
                        isPower = isPrimary && cat == "on",
                    )

                p.writable && p.valueList != null ->
                    out += Control.Choice(
                        svc.iid, p.iid, full, isPrimary, quickW, rankW,
                        p.valueList.mapIndexed { i, v -> v.value to (t.value(svc.iid, p.iid, i) ?: v.description) },
                    )

                p.writable && p.valueRange != null && p.valueRange.size >= 3 ->
                    out += Control.Range(
                        svc.iid, p.iid, full, isPrimary, quickW, rankW,
                        p.valueRange[0], p.valueRange[1], p.valueRange[2], p.unit,
                    )

                includeReadouts && p.readable && !p.writable && p.format != "string" ->
                    out += Control.Readout(
                        svc.iid, p.iid, full, isPrimary, quickR, rankR, p.unit,
                        p.valueList.orEmpty()
                            .mapIndexed { i, v -> v.value to (t.value(svc.iid, p.iid, i) ?: v.description) },
                        cat,
                    )
            }
        }

        for (a in svc.actions) {
            val cat = a.type.urnCategory() ?: continue
            // 单入参且入参可枚举的收进来（空调设温、扫地机选吸力）；
            // 多入参、或入参没有枚举/整数范围约束的仍然不做——表上没有可用的自由输入。
            val arg = when (a.inputs.size) {
                0 -> null
                1 -> actArg(svc, a.inputs[0], t) ?: continue
                else -> continue
            }
            val label = t.action(svc.iid, a.iid) ?: a.description.ifEmpty { cat }
            val full = if (isPrimary) label else "$svcLabel · $label"
            // 遥控类服务整个都算常用——红外空调的全部功能就是这几个动作
            val quick = isPrimary || svcCat in ACTION_SERVICES
            out += Control.Act(
                svc.iid, a.iid, full, isPrimary, quick,
                rank = if (cat in POWER_ACTIONS) 0 else 1,
                arg = arg,
            )
        }
    }
    // 常用优先 → 电源开关 → rank → 主服务
    val sorted = out.sortedWith(
        compareByDescending<Control> { it.quick }
            .thenByDescending { it is Control.Toggle && it.isPower }
            .thenBy { it.rank }
            .thenByDescending { it.primary },
    )
    return sorted.capQuick()
}

/**
 * 单入参动作的入参描述。入参 piid 指向同一 service 里的属性，
 * 档位从它的 value-list（原样 + 翻译）或 value-range（枚举/采样）来。
 */
private fun actArg(svc: SpecService, piid: Int, t: Translations): Control.ActArg? {
    val p = svc.properties.firstOrNull { it.iid == piid } ?: return null
    val opts = when {
        p.valueList != null -> p.valueList.mapIndexed { i, v ->
            v.value to (t.value(svc.iid, p.iid, i) ?: v.description)
        }
        p.valueRange != null && p.valueRange.size >= 3 ->
            rangeOptions(p.valueRange[0], p.valueRange[1], p.valueRange[2], p.unit)
        else -> null
    }
    return opts?.takeIf { it.isNotEmpty() }?.let { Control.ActArg(piid, it) }
}

/**
 * 常用集合的上限。
 *
 * 「主服务里的可写项默认都算常用」解决了空卡片，但另一头也会失控：语料里
 * rangefinder 出到 46 个可写项、投影仪 18 个、tv-box 20 个。33mm 屏上给一台设备
 * 铺 46 个 chip 和给它 0 个一样没用。
 *
 * 超出上限的降级为非常用——**它们仍留在 controls 里**，只是不进表上的默认视图，
 * 需要时可以另开一屏展示。白名单命中的（rank 0）永远排在前面，先被保留。
 *
 * 动作不设上限：投影仪的方向键、红外电视的音量键本来就是一整套，
 * 砍掉一半就等于砍掉了这个遥控器。
 */
private const val MAX_QUICK_WRITE = 8
private const val MAX_QUICK_READ = 6

private fun List<Control>.capQuick(): List<Control> {
    var write = 0
    var read = 0
    return map { c ->
        if (!c.quick || c is Control.Act) return@map c
        val over = if (c is Control.Readout) ++read > MAX_QUICK_READ else ++write > MAX_QUICK_WRITE
        if (!over) c else when (c) {
            is Control.Toggle -> c.copy(quick = false)
            is Control.Range -> c.copy(quick = false)
            is Control.Choice -> c.copy(quick = false)
            is Control.Readout -> c.copy(quick = false)
            is Control.Act -> c
        }
    }
}

// ---------- 拉取与缓存 ----------

/**
 * spec 服务器免鉴权，所以设备建模可以完全脱离登录来调试。
 * 缓存 key 直接用 urn 原文：urn 自带版本后缀，固件升级会产生新 urn＝新 key，
 * 失效逻辑是免费的，不需要 TTL。
 */
class SpecClient(private val cacheDir: java.io.File? = null) {

    private val client = MiHttp.client(readTimeoutSec = 60, callTimeoutSec = 90)
    // 并发预取会同时命中它：首次启动要为一屋子未知设备拉 spec，串行等不起
    private val mem = java.util.concurrent.ConcurrentHashMap<String, SpecInstance>()

    /** 这个型号的 spec 是否已经在本地——用来判断首启要不要给用户看进度。 */
    fun isCached(urn: String): Boolean =
        mem.containsKey(urn) || cacheDir?.resolve(sha1("$urn.spec") + ".json")?.isFile == true

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

    companion object {
        /** 全量 model → urn 索引，免登录。用来做覆盖率审计，也是「这个型号有没有 spec」的唯一权威。 */
        fun instances(): List<InstanceRef> {
            val c = MiHttp.client(readTimeoutSec = 120, callTimeoutSec = 180)
            val body = MiHttp.getText(c, "${SPEC_BASE}miot-spec-v2/instances?status=all")
            val arr = (miJson.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)["instances"]
            return miJson.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(InstanceRef.serializer()),
                arr!!,
            )
        }
    }
}

@Serializable
data class InstanceRef(
    val model: String,
    val type: String,
    val status: String = "",
    val version: Int = 0,
)
