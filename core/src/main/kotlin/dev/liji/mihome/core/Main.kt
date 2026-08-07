package dev.liji.mihome.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 桌面验证入口。
 *
 * 存在的理由：刷一次表要经 NAS + 等充电窗口，而这里能在 Mac 上以秒级循环
 * 把整条小米协议跑通、真的把卧室灯点亮。协议冻结之后才开始写 Android 代码。
 */
private val storeFile = File(System.getProperty("user.home"), ".mihome-core.properties")
private val store: Store by lazy { FileStore(storeFile) }
private val auth: MiAuth by lazy { MiAuth(store) }
private val api: MiApi by lazy { MiApi(store, auth, verbose = System.getenv("MIHOME_VERBOSE") != null) }

fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: return usage()
    val rest = args.drop(1)
    try {
        when (cmd) {
            "selftest" -> selftest()
            "login-pw" -> loginPw(rest)
            "login-qr" -> loginQr()
            "qr-gen" -> qrGen()
            "probe" -> probe()
            "homes" -> homes()
            "devices" -> devices()
            "get" -> get(rest)
            "set" -> set(rest)
            "action" -> action(rest)
            "spec" -> spec(rest)
            "controls-urn" -> controlsUrn(rest)
            "bundle" -> bundle(rest)
            "icons" -> icons(rest)
            "raw" -> raw(rest)
            "session" -> session()
            "logout" -> { store.clearSession(); println("已清除会话（deviceId 保留）") }
            else -> usage()
        }
    } catch (e: MiNeedVerifyException) {
        System.err.println("✗ ${e.message}")
        e.captchaUrl?.let { System.err.println("  captchaUrl = $it") }
        e.notificationUrl?.let { System.err.println("  notificationUrl = $it") }
        kotlin.system.exitProcess(2)
    } catch (e: Exception) {
        System.err.println("✗ ${e::class.simpleName}: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}

/** 把米家原生设备图标抓进 assets。见 MiIcons 里为什么这件事必须在构建期做。 */
private fun icons(a: List<String>) {
    require(a.isNotEmpty()) { "用法: icons <outdir> [model...]" }
    val models = a.drop(1).ifEmpty {
        api.devices(store.get("homeOwnerUid")!!.toLong(), store.get("homeId")!!.toLong())
            .filter { !it.isBle }.map { it.model }
    }
    val n = MiIcons.fetchInto(File(a[0]), models)
    println("\n$n/${models.distinct().size} 个图标已就位")
}

/** 原样打印任意端点的响应。类型化封装只挑走了用得上的字段，排查时需要看全貌。 */
private fun raw(a: List<String>) {
    require(a.isNotEmpty()) { "用法: raw <path> [jsonBody]" }
    println(api.post(a[0], a.getOrNull(1) ?: "{}"))
}

private fun usage() = println(
    """
    用法: <cmd> [args]
      selftest                       本地自检，不联网
      login-qr                       表上同款扫码登录（终端画二维码，用米家 App 扫）
      qr-gen                         只生成二维码不等扫码（验证生成链路用）
      login-pw <user> <pass>         密码登录（仅桌面用；表上不暴露）
      probe                          全栈证明：走一次签名接口 home/profile
      homes                          家庭与房间列表
      devices                        设备列表（含真实 spec_type urn）
      get <did> <siid> <piid> [...]  批量读属性
      set <did> <siid> <piid> <val>  写属性
      action <did> <siid> <aiid>     调用 action
      spec <urn>                     打印 MIoT spec 树（免登录）
      controls-urn <urn>             打印归约出的控件（免登录，调 toControls 用）
      bundle <outdir> <urn>...       把 spec 与中文翻译写进 assets，供表上首启即用
      icons <outdir> [model...]       抓米家原生设备图标进 assets（不传 model 就取全部设备）
      raw <path> [json]              原样打印端点响应（看类型化封装吃掉了哪些字段）
      session                        导出会话 blob（供 adb --es session 注入）
      logout                         清除会话
    环境变量 MIHOME_VERBOSE=1 打印请求与响应
    """.trimIndent(),
)

private fun selftest() {
    val nonce = MiCrypto.randomNonce()
    println("nonce       = $nonce")
    println("deviceId    = ${store.deviceId()}")
    println("signedNonce = ${MiCrypto.signedNonce("MDEyMzQ1Njc4OWFiY2RlZg==", nonce)}")
    println("proxy       = ${MiHttp.envProxy() ?: "直连"}")
    println("store       = $storeFile")
    println("session     = ${if (store.loadSession() != null) "已存在" else "无"}")
}

private fun loginPw(a: List<String>) {
    require(a.size >= 2) { "用法: login-pw <user> <pass>" }
    val s = auth.loginWithPassword(a[0], a[1])
    println("✓ 登录成功 userId=${s.userId}")
}

private fun loginQr() {
    val ch = auth.startQrLogin()
    println("请用米家 App 扫码（5 分钟内有效）：\n")
    printQr(ch.qrData)
    val png = File(System.getProperty("java.io.tmpdir"), "mihome-qr.png")
    writeQrPng(ch.qrData, png)
    println("\n二维码内容: ${ch.qrData}")
    println("PNG 已写入: $png")
    println("\n等待扫码确认…")
    val s = auth.awaitQrScan(ch.lp)
    println("✓ 登录成功 userId=${s.userId}")
}

/** 只验证生成链路（URL 拼装、&&&START&&& 剥离、JSON 形状），不进入长轮询。 */
private fun qrGen() {
    val ch = auth.startQrLogin()
    println("✓ 二维码已生成")
    println("  qrData = ${ch.qrData}")
    println("  lp     = ${ch.lp}")
    val png = File(System.getProperty("java.io.tmpdir"), "mihome-qr.png")
    writeQrPng(ch.qrData, png)
    println("  PNG    = $png")
    println()
    printQr(ch.qrData)
}

private fun probe() {
    val r = api.profile()
    val res = r["result"]?.jsonObject
    println("✓ 签名接口连通 code=${r.code()}  昵称=${res?.get("nickname")?.jsonPrimitive?.content}  userid=${res?.get("userid")}")
}

private fun homes() {
    val r = api.getHomes()
    val list = r["result"]?.jsonObject?.get("homelist")?.jsonArray ?: return println("无家庭")
    list.forEach { h ->
        val o = h.jsonObject
        val id = o["id"]?.jsonPrimitive?.content
        val uid = o["uid"]?.jsonPrimitive?.longOrNull
        println("家庭 id=$id uid=$uid name=${o["name"]?.jsonPrimitive?.content} 设备数=${o["dids"]?.jsonArray?.size}")
        o["roomlist"]?.jsonArray?.forEach { rm ->
            val ro = rm.jsonObject
            println("   房间 ${ro["name"]?.jsonPrimitive?.content}  dids=${ro["dids"]?.jsonArray?.size}")
        }
        if (store.get("homeId") == null) {
            store.set("homeId", id); store.set("homeOwnerUid", uid?.toString())
            println("   ↑ 已缓存为默认家庭")
        }
    }
}

private fun devices() {
    val homeId = store.get("homeId")?.toLong() ?: run { homes(); store.get("homeId")!!.toLong() }
    val uid = store.get("homeOwnerUid")!!.toLong()
    val r = api.getDevices(uid, homeId)
    val devs = r["result"]?.jsonObject?.get("device_info")?.jsonArray ?: return println("无设备")
    println("共 ${devs.size} 个设备\n")
    println("%-22s %-28s %-6s %s".format("did", "model", "在线", "name / spec_type"))
    devs.forEach { d ->
        val o = d.jsonObject
        val did = o["did"]?.jsonPrimitive?.content.orEmpty()
        val ble = did.startsWith("blt.")
        println(
            "%-22s %-28s %-6s %s".format(
                did + (if (ble) " (BLE)" else ""),
                o["model"]?.jsonPrimitive?.content.orEmpty(),
                if (o["isOnline"]?.jsonPrimitive?.content == "true") "✓" else "✗",
                o["name"]?.jsonPrimitive?.content.orEmpty(),
            ),
        )
        o["spec_type"]?.jsonPrimitive?.content?.let { println("%-22s   %s".format("", it)) }
    }
}

private fun get(a: List<String>) {
    require(a.size >= 3 && (a.size - 1) % 2 == 0) { "用法: get <did> <siid> <piid> [<siid> <piid> ...]" }
    val did = a[0]
    val refs = a.drop(1).chunked(2).map { PropRef(did, it[0].toInt(), it[1].toInt()) }
    api.propGet(refs).forEach {
        val ok = it.code == 0
        println("s${it.siid} p${it.piid} = ${if (ok) it.value.toString() else "—  (code=${it.code})"}")
    }
}

private fun set(a: List<String>) {
    require(a.size == 4) { "用法: set <did> <siid> <piid> <value>" }
    val r = api.propSet(listOf(PropRef(a[0], a[1].toInt(), a[2].toInt()) to parseValue(a[3])))
    println("code=${r.code()}  result=${r["result"]}")
}

private fun action(a: List<String>) {
    require(a.size >= 3) { "用法: action <did> <siid> <aiid> [args…]" }
    val r = api.action(a[0], a[1].toInt(), a[2].toInt(), a.drop(3).map { parseValue(it) })
    println("code=${r.code()}  result=${r["result"]}")
}

private val specClient by lazy {
    SpecClient(File(System.getProperty("user.home"), ".mihome-spec-cache"))
}

private fun spec(a: List<String>) {
    require(a.isNotEmpty()) { "用法: spec <urn>" }
    val s = specClient.spec(a[0])
    val t = specClient.translations(a[0])
    println("${s.type}\n${s.description}\n")
    s.services.forEach { svc ->
        println("siid ${svc.iid}  ${svc.type.urnCategory()}  ${t.service(svc.iid) ?: svc.description}")
        svc.properties.forEach { p ->
            val extra = when {
                p.valueList != null -> " [" + p.valueList.mapIndexed { i, v ->
                    "${v.value}=${t.value(svc.iid, p.iid, i) ?: v.description}"
                }.joinToString(", ") + "]"
                p.valueRange != null -> " range=${p.valueRange}"
                else -> ""
            }
            println(
                "    p${p.iid}  ${p.type.urnCategory()}  ${p.format}  ${p.access}  " +
                    "${t.property(svc.iid, p.iid) ?: p.description}${p.unit?.let { " ($it)" } ?: ""}$extra",
            )
        }
    }
}

private fun controlsUrn(a: List<String>) {
    require(a.isNotEmpty()) { "用法: controls-urn <urn>" }
    val s = specClient.spec(a[0])
    val t = specClient.translations(a[0])
    val controls = s.toControls(t)
    val quick = controls.count { it.quick }
    println("${s.type}  ->  ${controls.size} 个控件，其中常用 $quick（表上默认只显示这些）\n")
    controls.forEach { c ->
        val tag = if (c.quick) "★" else if (c.primary) "主" else "  "
        when (c) {
            is Control.Toggle -> println("$tag 开关   s${c.siid} p${c.piid}  ${c.label}${if (c.isPower) "   ← 电源" else ""}")
            is Control.Range -> println("$tag 滑块   s${c.siid} p${c.piid}  ${c.label}  ${c.min}–${c.max} step ${c.step} ${c.unit.orEmpty()}")
            is Control.Choice -> println("$tag 选择   s${c.siid} p${c.piid}  ${c.label}  ${c.options.joinToString(" / ") { it.second }}")
            is Control.Readout -> println("$tag 只读   s${c.siid} p${c.piid}  ${c.label} ${c.unit.orEmpty()}")
        }
    }
}

/**
 * 预置 spec 到 assets。表上首启就不必对着蓝牙代理拉 spec——
 * 那是交互路径上最容易吃满 30 秒最坏延迟的一步。
 */
private fun bundle(a: List<String>) {
    require(a.size >= 2) { "用法: bundle <outdir> <urn>..." }
    val out = File(a[0]).apply { mkdirs() }
    val client = SpecClient(out) // 缓存目录直接就是 assets 目录，写进去即完成预置
    a.drop(1).forEach { urn ->
        val s = client.spec(urn)
        val t = client.translations(urn)
        val n = s.toControls(t).count { it.quick }
        println("✓ ${s.type}  服务 ${s.services.size} 个，常用控件 $n 个")
    }
    println("\n已写入 ${out.absolutePath}（${out.listFiles()?.size ?: 0} 个文件）")
}

private fun session() {
    val s = store.loadSession() ?: return println("无会话")
    val blob = listOf(s.userId, s.cUserId, s.passToken, s.ssecurity, s.serviceToken, s.deviceId)
        .joinToString("\n")
    println(Base64.getUrlEncoder().withoutPadding().encodeToString(blob.toByteArray()))
}

private fun parseValue(s: String): JsonElement = when {
    s == "true" -> JsonPrimitive(true)
    s == "false" -> JsonPrimitive(false)
    s.toLongOrNull() != null -> JsonPrimitive(s.toLong())
    s.toDoubleOrNull() != null -> JsonPrimitive(s.toDouble())
    else -> JsonPrimitive(s)
}

// ---- 二维码渲染 ----

private fun qrMatrix(data: String, size: Int): BitMatrix = QRCodeWriter().encode(
    data,
    BarcodeFormat.QR_CODE,
    size,
    size,
    mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        EncodeHintType.MARGIN to 2, // 静默区压到 2 个模块，小屏和终端都吃紧
        EncodeHintType.CHARACTER_SET to "UTF-8",
    ),
)

/** 用半块字符 + ANSI 背景色画，深浅终端都能扫；不靠终端主题猜颜色。 */
private fun printQr(data: String) {
    val m = qrMatrix(data, 0) // 0 = 让 zxing 用最小尺寸
    val w = m.width
    val h = m.height
    val black = "[48;5;0m"
    val white = "[48;5;15m"
    val fgBlack = "[38;5;0m"
    val fgWhite = "[38;5;15m"
    val reset = "[0m"
    var y = 0
    while (y < h) {
        val sb = StringBuilder()
        for (x in 0 until w) {
            val top = m.get(x, y)
            val bottom = if (y + 1 < h) m.get(x, y + 1) else false
            sb.append(if (top) fgBlack else fgWhite)
            sb.append(if (bottom) black else white)
            sb.append('▀') // ▀
        }
        sb.append(reset)
        println(sb)
        y += 2
    }
}

private fun writeQrPng(data: String, out: File) {
    val m = qrMatrix(data, 480)
    val img = BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until m.width) for (y in 0 until m.height) {
        img.setRGB(x, y, if (m.get(x, y)) Color.BLACK.rgb else Color.WHITE.rgb)
    }
    ImageIO.write(img, "png", out)
}
