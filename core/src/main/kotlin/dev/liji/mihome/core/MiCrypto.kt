package dev.liji.mihome.core

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 小米云端接口的签名算法。纯 JDK 实现，不依赖 Android——
 * 这样整条协议能先在 Mac 上跑通，不必为每次验证走一趟 NAS 刷机。
 */
object MiCrypto {

    /** nonce 的字符表刻意选成 base64 字母表的子集：16 字符恰好能解码成 12 字节。 */
    private const val NONCE_CHARS = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DEVICE_ID_CHARS = "0123456789ABCDEF"

    private val enc: Base64.Encoder = Base64.getEncoder()
    private val dec: Base64.Decoder = Base64.getDecoder()

    fun randomNonce(rnd: Random = Random.Default): String =
        buildString(16) { repeat(16) { append(NONCE_CHARS[rnd.nextInt(NONCE_CHARS.length)]) } }

    /**
     * 生成一次后必须持久化，永不重生成。
     * 不能用 Settings.Secure.ANDROID_ID：它按签名 key 隔离，卸载重装就会变，
     * 而 deviceId 已经绑进 passport 会话，一变会话就静默失效。
     */
    fun randomDeviceId(rnd: Random = Random.Default): String =
        buildString(16) { repeat(16) { append(DEVICE_ID_CHARS[rnd.nextInt(DEVICE_ID_CHARS.length)]) } }

    /** 密码登录用：大写十六进制 MD5。 */
    fun md5Upper(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

    /** signedNonce = Base64( SHA256( b64d(ssecurity) ‖ b64d(nonce) ) )，裸字节拼接无分隔。 */
    fun signedNonce(ssecurity: String, nonce: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(dec.decode(ssecurity))
        sha.update(dec.decode(nonce))
        return enc.encodeToString(sha.digest())
    }

    /**
     * 注意这里的不对称：明文里嵌的是 signedNonce 的 **base64 文本**，
     * 而 HMAC 的密钥是它 **解码后的 32 字节**。抄错任一处都签不出来。
     *
     * @param uri  接口路径，API 基址替换成 "/"，如 "/miotspec/prop/get"
     * @param data 原始 JSON 串，未经 URL 编码；必须与最终发出去的 data 字段逐字节相同
     */
    fun sign(uri: String, signedNonce: String, nonce: String, data: String): String {
        val plaintext = "$uri&$signedNonce&$nonce&data=$data"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(dec.decode(signedNonce), "HmacSHA256"))
        return enc.encodeToString(mac.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }
}
