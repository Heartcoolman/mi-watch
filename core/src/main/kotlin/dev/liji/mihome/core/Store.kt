package dev.liji.mihome.core

import java.io.File
import java.util.Properties

/** 唯一的抽象层：桌面用文件，Android 用 SharedPreferences。 */
interface Store {
    fun get(k: String): String?
    fun set(k: String, v: String?)
}

class FileStore(private val file: File) : Store {
    private val p = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    override fun get(k: String): String? = p.getProperty(k)?.takeIf { it.isNotEmpty() }

    override fun set(k: String, v: String?) {
        if (v == null) p.remove(k) else p.setProperty(k, v)
        file.outputStream().use { p.store(it, "mi-watch core") }
    }
}

/**
 * ssecurity 和 serviceToken 每次刷新都会轮换，绝不能在拦截器里用 val 捕获，
 * 必须每次请求现读——否则刷新之后所有签名失败，且没有明显线索。
 * passToken 是长期凭证，永不轮换。
 */
data class Session(
    val userId: String,
    val cUserId: String,
    val passToken: String,
    val ssecurity: String,
    val serviceToken: String,
    val deviceId: String,
)

fun Store.loadSession(): Session? {
    val userId = get("userId") ?: return null
    return Session(
        userId = userId,
        cUserId = get("cUserId") ?: return null,
        passToken = get("passToken") ?: return null,
        ssecurity = get("ssecurity") ?: return null,
        serviceToken = get("serviceToken") ?: return null,
        deviceId = get("deviceId") ?: return null,
    )
}

fun Store.saveSession(s: Session) {
    set("userId", s.userId)
    set("cUserId", s.cUserId)
    set("passToken", s.passToken)
    set("ssecurity", s.ssecurity)
    set("serviceToken", s.serviceToken)
    set("deviceId", s.deviceId)
}

fun Store.clearSession() {
    // region 也要清：换个账号很可能归属另一个区域
    listOf("userId", "cUserId", "passToken", "ssecurity", "serviceToken", "region")
        .forEach { set(it, null) }
    // deviceId 故意保留：它绑在 passport 会话上，重登时复用同一个更稳。
}

/** deviceId 生成一次就永久固定。 */
fun Store.deviceId(): String =
    get("deviceId") ?: MiCrypto.randomDeviceId().also { set("deviceId", it) }
