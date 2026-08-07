package dev.liji.mihome

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 显式选择出口网络。
 *
 * 手表上通常同时存在两条已验证的网络：
 *   - WIFI            手表自身 Wi-Fi，带 TRANSPORT_PRIMARY，默认优先
 *   - COMPANION_PROXY 经蓝牙由配对手机代理（transport 是 BLUETOOTH）
 * 不绑定时系统按打分选，Wi-Fi 在就走 Wi-Fi。
 *
 * 绑 Wi-Fi 的机制抄自 ~/watch-ha 的 Net.bindWifi，但**目的相反**：
 * 那边是因为 HA 在局域网、蓝牙代理够不到；这里是因为小米登录是多跳 302 +
 * Cookie 状态机 + 长轮询，怕蓝牙代理扛不住。
 */
object Net {

    /**
     * [block] 内新建的 HTTP 客户端会走绑定后的网络。
     * 必须在块内构造客户端：OkHttp 按 client 缓存连接和 DNS，
     * 绑定前造好的 client 会继续复用原网络的 socket，绑定就白做了。
     */
    suspend fun <T> withWifi(ctx: Context, timeoutMs: Long = 20_000, block: suspend (bound: Boolean) -> T): T =
        withTransport(ctx, NetworkCapabilities.TRANSPORT_WIFI, "wifi", timeoutMs, block)

    /*
     * 【不要给蓝牙写一个 bindBluetooth】——试过，是死路，实测证据如下：
     *
     *   NetdEventListenerService: DNS Requested by 370, 10188(dev.liji.mihome),
     *                             255(TIMEOUT), isBlocked=true, 11527ms
     *
     * 把进程 bindProcessToNetwork 到 COMPANION_PROXY(transport=BLUETOOTH) 之后，
     * DNS 直接发到该网络自报的 DNS 地址上，超时且被阻断，请求永久挂起
     * （连 OkHttp 的 callTimeout 都不触发，因为卡在解析阶段）。
     *
     * 蓝牙代理只在**系统自己做路由**时才工作（走的是系统的 ProxyConnectionV1），
     * 手动绑 socket 恰好绕过了那套机制。
     *
     * 正确做法：什么都不做。Wi-Fi 不可用时系统会自动把默认网络切到 COMPANION_PROXY，
     * 应用侧照常发 HTTPS 即可——实测可用，见 15:00 那批纯蓝牙读写记录。
     */

    /** 诊断用：把当前所有可用网络打进日志，出问题时不用猜。 */
    fun dumpNetworks(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        cm.allNetworks.forEach { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@forEach
            val kinds = buildList {
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BT")
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELL")
            }
            Flog.i(
                "网络 $n ${kinds.joinToString("+")}" +
                    " internet=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}" +
                    " validated=${c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}" +
                    (if (n == active) "  ← 默认" else ""),
            )
        }
    }

    private suspend fun <T> withTransport(
        ctx: Context,
        transport: Int,
        name: String,
        timeoutMs: Long,
        block: suspend (bound: Boolean) -> T,
    ): T {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val latch = CountDownLatch(1)
        val net = AtomicReference<Network?>()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                net.set(network); latch.countDown()
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(transport)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        var registered = false
        var bound = false
        return try {
            cm.requestNetwork(req, cb)
            registered = true
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val n = net.get()
            if (ok && n != null) {
                cm.bindProcessToNetwork(n)
                bound = true
                Flog.i("$name 已绑定")
            } else {
                // 不硬失败：没连 Wi-Fi 的场合应当继续用默认网络试，而不是彻底不可用
                Flog.w("$name ${timeoutMs}ms 内未就绪，回落默认网络")
            }
            block(bound)
        } finally {
            // try/finally 是硬要求：漏释放会把射频永久锁开（watch-ha Relay.kt:39 记录过这个坑）
            if (registered) runCatching { cm.unregisterNetworkCallback(cb) }
            runCatching { cm.bindProcessToNetwork(null) }
            if (bound) Flog.i("$name 已释放")
        }
    }
}
