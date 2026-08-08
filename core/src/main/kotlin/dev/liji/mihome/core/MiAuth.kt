package dev.liji.mihome.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

@Serializable
data class ServiceLoginResp(
    val code: Int = -1,
    val qs: String? = null,
    val sid: String? = null,
    @SerialName("_sign") val sign: String? = null,
    val callback: String? = null,
    // location 和 ssecurity 必须可空：未鉴权的首次调用返回 code 70016 时两者都缺，
    // 参考实现把它们声明成必填，于是在这里直接抛 MissingFieldException，
    // 导致 70016/81003/87001 的错误码映射根本走不到。
    val location: String? = null,
    val ssecurity: String? = null,
    val desc: String? = null,
    val description: String? = null,
)

@Serializable
data class LoginResp(
    val code: Int = -1,
    val desc: String? = null,
    val description: String? = null,
    val result: String? = null,
    val location: String? = null,
    val userId: Long? = null,
    val cUserId: String? = null,
    val nonce: Long? = null,
    val ssecurity: String? = null,
    val psecurity: String? = null,
    val passToken: String? = null,
    // 这三个字段参考实现里零读取点，所以它完全不知道自己是被 2FA 挡住了。我们要读。
    val securityStatus: Int? = null,
    val notificationUrl: String? = null,
    val captchaUrl: String? = null,
)

@Serializable
data class QrResp(
    val code: Int = -1,
    val desc: String? = null,
    val description: String? = null,
    val loginUrl: String? = null,
    val lp: String? = null,
    val timeout: Int? = null,
)

/** qrData 是渲染进二维码的原始字符串；lp 是要去阻塞轮询的地址。 */
data class QrChallenge(val qrData: String, val lp: String)

class MiAuth(private val store: Store) {

    private val jar = BagCookieJar()
    private val client = MiHttp.client(jar)

    /**
     * 长轮询专用 client：读超时设为无限，用 callTimeout 表达「二维码过期」。
     * 这样 SocketTimeoutException（连接层，该重试）和 InterruptedIOException（过期，该换码）
     * 才能区分开——参考实现用 5 分钟读超时同时表达两件事，在蓝牙链路上会频繁误判。
     */
    private val lpClient = MiHttp.client(jar, readTimeoutSec = 0, callTimeoutSec = 300, connectTimeoutSec = 30)

    fun loginWithPassword(user: String, pass: String): Session {
        jar.clear()
        val sl = serviceLogin()
        val form = FormBody.Builder()
            .add("qs", sl.qs.orEmpty())
            .add("sid", sl.sid ?: MIOT_SID)
            .add("_sign", sl.sign.orEmpty())
            .add("callback", sl.callback ?: STS_CALLBACK)
            .add("user", user)
            .add("hash", MiCrypto.md5Upper(pass))
            .add("_json", "true")
            .build()
        val body = client.newCall(MiHttp.req(SERVICE_LOGIN_AUTH_URL).post(form).build())
            .execute().use { it.body?.string().orEmpty() }
        // 参考实现漏了这一步：auth2 的响应同样带 &&&START&&& 前缀。
        val login = miJson.decodeFromString<LoginResp>(body.stripStartPrefix())
        checkLogin(login)
        return finish(login, login.ssecurity, login.location)
    }

    fun startQrLogin(): QrChallenge {
        jar.clear()
        val url = QR_LOGIN_URL.toHttpUrl().newBuilder()
            .addQueryParameter("_qrsize", "240")
            .addQueryParameter("qs", "?sid=$MIOT_SID")
            .addQueryParameter("callback", STS_CALLBACK)
            .addQueryParameter("sid", MIOT_SID)
            .addQueryParameter("serviceParam", "")
            .addQueryParameter("_locale", "zh_CN")
            .addQueryParameter("_dc", System.currentTimeMillis().toString())
            .build().toString()
        val r = miJson.decodeFromString<QrResp>(MiHttp.getText(client, url).stripStartPrefix())
        if (r.code != 0) throw MiException("二维码生成失败 code=${r.code} ${r.desc.orEmpty()}")
        return QrChallenge(
            qrData = r.loginUrl ?: throw MiException("二维码响应缺 loginUrl"),
            lp = r.lp ?: throw MiException("二维码响应缺 lp"),
        )
    }

    /** 阻塞直到用户扫码确认。过期抛 [MiQrExpiredException]，连接问题抛 [MiException]。 */
    fun awaitQrScan(lp: String): Session {
        val body = try {
            lpClient.newCall(MiHttp.req(lp).get().build()).execute().use { it.body?.string().orEmpty() }
        } catch (e: SocketTimeoutException) {
            throw MiException("长轮询连接超时，可重试: ${e.message}")
        } catch (e: InterruptedIOException) {
            throw MiQrExpiredException()
        }
        val login = miJson.decodeFromString<LoginResp>(body.stripStartPrefix())
        checkLogin(login)
        // 长轮询返回的是 passport 层的登录结果，未必带 xiaomiio 这个 sid 的 location/ssecurity
        // （实测二维码 URL 里确实带 sid=xiaomiio，但 lp 响应的字段仍要以重取为准）。
        // 带上刚拿到的 cookie 再走一次 serviceLogin，拿到给 STS 用的那一对。
        val sl = serviceLogin()
        return finish(
            login,
            sl.ssecurity ?: throw MiException("扫码后重取 ssecurity 失败 code=${sl.code}"),
            sl.location ?: throw MiException("扫码后重取 location 失败 code=${sl.code}"),
        )
    }

    /** serviceToken 失效时调用。ssecurity 也会一起换新。 */
    fun refresh(s: Session): Session {
        jar.clear()
        // 注意：刷新时 cookie 名是 deviceId，而签名请求的 Cookie 头里是 PassportDeviceId。
        jar.seed("deviceId", s.deviceId)
        jar.seed("userId", s.userId)
        jar.seed("cUserId", s.cUserId)
        jar.seed("passToken", s.passToken)
        val sl = serviceLogin()
        // passport 应答了但不给令牌 = passToken 也失效了，只能重新扫码。
        // 网络类失败（IOException）不走这里——那是该重试的错，不是该登出的错。
        val ssecurity = sl.ssecurity
            ?: throw MiSessionExpiredException("会话已失效：无 ssecurity, code=${sl.code} ${sl.desc.orEmpty()}")
        val location = sl.location
            ?: throw MiSessionExpiredException("会话已失效：无 location, code=${sl.code}")
        val out = s.copy(ssecurity = ssecurity, serviceToken = harvestServiceToken(location))
        store.saveSession(out)
        return out
    }

    private fun serviceLogin(): ServiceLoginResp =
        miJson.decodeFromString(MiHttp.getText(client, SERVICE_LOGIN_URL).stripStartPrefix())

    private fun finish(login: LoginResp, ssecurity: String?, location: String?): Session {
        val s = Session(
            userId = (login.userId ?: throw MiException("登录响应缺 userId")).toString(),
            cUserId = login.cUserId ?: throw MiException("登录响应缺 cUserId"),
            passToken = login.passToken ?: throw MiException("登录响应缺 passToken"),
            ssecurity = ssecurity ?: throw MiException("登录响应缺 ssecurity"),
            serviceToken = harvestServiceToken(location ?: throw MiException("登录响应缺 location")),
            deviceId = store.deviceId(),
        )
        store.saveSession(s)
        return s
    }

    /** serviceToken 只以 Set-Cookie 形式出现在 STS 跳转链路上，jar 在每一跳都会收。 */
    private fun harvestServiceToken(location: String): String {
        client.newCall(MiHttp.req(location).get().build()).execute().close()
        return jar.value("serviceToken")
            ?: throw MiException("未从 Set-Cookie 取到 serviceToken（当前 cookie: ${jar.names()}）")
    }

    private fun checkLogin(r: LoginResp) {
        if (r.code == 0 && r.notificationUrl.isNullOrEmpty()) return
        if (!r.notificationUrl.isNullOrEmpty() || r.code == 81003 || r.code == 87001) {
            throw MiNeedVerifyException(
                "需要验证码/二次验证 (code=${r.code} ${r.desc.orEmpty()}) —— 改用扫码登录可绕过，" +
                    "因为用已登录的米家 App 扫码本身就是第二因子",
                r.captchaUrl,
                r.notificationUrl,
            )
        }
        throw MiException(
            when (r.code) {
                20003 -> "用户名无效 (20003)"
                22009 -> "包名被拒 (22009)"
                70002, 70016 -> "账号或密码错误 (code=${r.code})"
                else -> "登录失败 code=${r.code} ${r.desc.orEmpty()} ${r.description.orEmpty()}"
            },
        )
    }
}
