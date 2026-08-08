package dev.liji.mihome.core

import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

const val MI_UA = "APP/com.xiaomi.mihome APPV/6.0.103 iosPassportSDK/3.9.0 iOS/14.4 miHSTS"
const val MIOT_SID = "xiaomiio"
/**
 * 账号归属的区域。**登录流程与区域无关**——passport（account.xiaomi.com）和
 * STS 回调（sts.api.io.mi.com）全球统一，只有登录之后的业务接口带区域前缀。
 * 这一点由 micloud 和 Xiaomi-cloud-tokens-extractor 两个独立实现的源码互相印证。
 *
 * 所以不需要让用户在登录前选区域：登录成功后逐个试一遍、谁能返回家庭列表就是谁，
 * 结果落盘，之后不再探测。选错区域的表现是「登录成功但一个设备都没有」，
 * 这是开源项目里最常见也最难自查的一类 issue，能自动定就别让用户猜。
 */
val MIOT_REGIONS = listOf("cn", "de", "sg", "us", "ru", "i2", "tw")

fun miotApiBase(region: String?): String {
    val r = region?.lowercase().orEmpty()
    return if (r.isEmpty() || r == "cn") "https://api.io.mi.com/app/" else "https://$r.api.io.mi.com/app/"
}
const val SPEC_BASE = "https://miot-spec.org/"
const val SERVICE_LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin?sid=$MIOT_SID&_json=true"
const val SERVICE_LOGIN_AUTH_URL = "https://account.xiaomi.com/pass/serviceLoginAuth2"
const val QR_LOGIN_URL = "https://account.xiaomi.com/longPolling/loginUrl"
const val STS_CALLBACK = "https://sts.api.io.mi.com/sts"

private const val START_PREFIX = "&&&START&&&"

val miJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 小米账号接口的响应都带这个防 JSON 劫持前缀。
 * 参考实现是无条件 substring(11)，这里改成有则剥、无则原样——
 * 出错时能看到真实响应体，而不是被砍掉前 11 个字符的乱码。
 */
fun String.stripStartPrefix(): String =
    if (startsWith(START_PREFIX)) substring(START_PREFIX.length) else this

/**
 * 故意不做 URL 作用域隔离：passport 的 cookie 必须能送到 sts.api.io.mi.com，
 * 标准 CookieJar 会因域名不匹配而拒发，登录就断在最后一跳。
 *
 * 另一个白拿的好处：OkHttp 在**每一跳重定向**都会调 saveFromResponse，
 * 所以「只读最后一跳的 Set-Cookie」那类 bug 在这里结构上不可能发生。
 */
class BagCookieJar : CookieJar {
    private val bag = LinkedHashMap<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { bag[it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> = bag.values.toList()

    @Synchronized
    fun value(name: String): String? = bag[name]?.value

    @Synchronized
    fun seed(name: String, value: String, domain: String = "account.xiaomi.com") {
        bag[name] = Cookie.Builder().name(name).value(value).domain(domain).path("/").build()
    }

    @Synchronized
    fun clear() = bag.clear()

    @Synchronized
    fun names(): List<String> = bag.keys.toList()
}

object MiHttp {

    /**
     * 认标准的 HTTPS_PROXY / https_proxy 环境变量。
     * OkHttp 默认只看 JVM 系统属性，而 fork 出来的 JVM 不继承 Gradle 的 systemProp，
     * 在只能经代理出网的环境里会静默走直连然后超时。Android 上这两个变量为空，
     * 自动退回直连，由系统把流量代理到手机——正是我们要的。
     */
    fun envProxy(): Proxy? {
        val raw = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return null
        return runCatching {
            val u = URI(if (raw.contains("://")) raw else "http://$raw")
            Proxy(Proxy.Type.HTTP, InetSocketAddress(u.host, if (u.port > 0) u.port else 8080))
        }.getOrNull()
    }

    fun client(
        jar: CookieJar = CookieJar.NO_COOKIES,
        readTimeoutSec: Long = 30,
        callTimeoutSec: Long = 60,
        connectTimeoutSec: Long = 15,
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
        .also { b -> envProxy()?.let { b.proxy(it) } }
        .build()

    fun req(url: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", MI_UA)

    /** 阻塞 GET，返回响应体文本。调用方负责放到 IO 线程。 */
    fun getText(client: OkHttpClient, url: String): String =
        client.newCall(req(url).get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw MiException("GET $url -> HTTP ${r.code}: ${body.take(200)}")
            body
        }
}

open class MiException(message: String) : RuntimeException(message)

/** 需要验证码 / 二次验证。扫码登录可绕过，因为「用已登录的米家 App 扫」本身就是第二因子。 */
class MiNeedVerifyException(message: String, val captchaUrl: String?, val notificationUrl: String?) :
    MiException(message)

/** 二维码已过期，应重新生成一张。 */
class MiQrExpiredException : MiException("QR code expired")

/**
 * 会话彻底失效：serviceToken 过期且用 passToken 换新也失败了。
 * 与一般网络错误分开抛，是因为两者的出路完全不同——网络错该重试，
 * 这个错只能重新扫码。上层（表和 CLI）都要据此把用户送回登录，
 * 而不是甩一条看不懂的报错让人反复点刷新。
 */
class MiSessionExpiredException(message: String) : MiException(message)
