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
 * 把进程临时绑到手表自身 Wi-Fi。
 *
 * 机制抄自 ~/watch-ha 的 Net.bindWifi，但**目的正好相反**：
 * watch-ha 绑 Wi-Fi 是因为 HA 在局域网、蓝牙代理够不到；
 * 这里绑是因为小米登录是多跳 302 + Cookie 状态机 + 长轮询，
 * 蓝牙代理可能扛不住（MiWu issue #45/#57 的现象）。
 *
 * 日常控制不用这个——那是单次几百字节的 POST，蓝牙代理足够，
 * 而且常开 Wi-Fi 射频很耗电（watch-ha Relay.kt:39 记录过这个坑）。
 */
object Net {

    /**
     * [block] 内新建的 HTTP 客户端会走绑定后的网络。
     * 必须在块内构造客户端：OkHttp 按 client 缓存连接和 DNS，
     * 绑定前造好的 client 会继续复用蓝牙代理的 socket，绑定就白做了。
     *
     * Wi-Fi 在 [timeoutMs] 内没就绪不算失败——直接回落蓝牙继续试，
     * 硬失败只会让登录在没连 Wi-Fi 的场合彻底不可用。
     */
    suspend fun <T> withWifi(ctx: Context, timeoutMs: Long = 20_000, block: suspend (bound: Boolean) -> T): T {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val latch = CountDownLatch(1)
        val net = AtomicReference<Network?>()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                net.set(network)
                latch.countDown()
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
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
                Flog.i("wifi 已绑定，登录走手表自身网络")
            } else {
                Flog.w("wifi ${timeoutMs}ms 内未就绪，回落蓝牙代理")
            }
            block(bound)
        } finally {
            // try/finally 是硬要求：漏释放会把 Wi-Fi 射频永久锁开。
            if (registered) runCatching { cm.unregisterNetworkCallback(cb) }
            runCatching { cm.bindProcessToNetwork(null) }
            if (bound) Flog.i("wifi 已释放")
        }
    }
}
