package dev.liji.mihome.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 期望值由 Python 独立生成（hashlib/hmac/base64），不是本实现自己算的——
 * 实现验自己等于没验。生成脚本见计划文件 Stage 1。
 */
class MiCryptoTest {

    private val ssecurity = "MDEyMzQ1Njc4OWFiY2RlZg=="
    private val nonce = "aB3xY9zQ1mN7pL5k"
    private val data = """{"params":[{"did":"123456789","siid":2,"piid":1}]}"""

    @Test
    fun signedNonceMatchesPython() {
        assertEquals(
            "pPNJ3i4wXoCA/ByAFzOu56H70wkMoG9UaZgOHmqUWBk=",
            MiCrypto.signedNonce(ssecurity, nonce),
        )
    }

    @Test
    fun signatureMatchesPython() {
        val sn = MiCrypto.signedNonce(ssecurity, nonce)
        assertEquals(
            "kgI5fPxjNUgyITsM42VfDHGFhft76o1ZbVNayp26OCI=",
            MiCrypto.sign("/miotspec/prop/get", sn, nonce, data),
        )
    }

    @Test
    fun md5UpperKnownVector() {
        assertEquals("5F4DCC3B5AA765D61D8327DEB882CF99", MiCrypto.md5Upper("password"))
    }

    @Test
    fun signedNonceIs44Chars() {
        assertEquals(44, MiCrypto.signedNonce(ssecurity, nonce).length)
    }

    /** nonce 必须能被 base64 解码成恰好 12 字节，否则 signedNonce 的输入长度就错了。 */
    @Test
    fun nonceIsBase64DecodableTo12Bytes() {
        repeat(200) {
            val n = MiCrypto.randomNonce()
            assertEquals(16, n.length)
            assertTrue(n.all { c -> c.isLetterOrDigit() }, "unexpected char in $n")
            assertEquals(12, Base64.getDecoder().decode(n).size, "nonce $n did not decode to 12 bytes")
        }
    }

    @Test
    fun deviceIdIs16UpperHex() {
        repeat(50) {
            val d = MiCrypto.randomDeviceId()
            assertEquals(16, d.length)
            assertTrue(d.all { c -> c in "0123456789ABCDEF" }, "unexpected char in $d")
        }
    }

    /** 明文拼接顺序固定为 uri、signedNonce、nonce、data；换序必须签出不同结果。 */
    @Test
    fun signatureIsOrderSensitive() {
        val sn = MiCrypto.signedNonce(ssecurity, nonce)
        val a = MiCrypto.sign("/miotspec/prop/get", sn, nonce, data)
        val b = MiCrypto.sign("/miotspec/prop/set", sn, nonce, data)
        assertTrue(a != b)
    }
}
