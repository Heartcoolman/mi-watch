package dev.liji.mihome

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志。不是可选项：Stage 4 的决定性测试要关掉手表 Wi-Fi 只留蓝牙，
 * 而 adb over Wi-Fi 需要的正是刚关掉的那个 Wi-Fi——只能盲跑完再
 * `adb shell run-as dev.liji.mihome cat files/log.txt` 把日志捞出来。
 */
object Flog {
    private const val TAG = "mi-watch"
    private const val MAX = 200 * 1024

    private lateinit var file: File
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "log.txt")
    }

    fun i(msg: String) = write("I", msg)
    fun w(msg: String) = write("W", msg)

    fun e(msg: String, t: Throwable? = null) =
        write("E", if (t == null) msg else "$msg :: ${t::class.simpleName}: ${t.message}")

    /** 记录一次带耗时的操作——蓝牙代理的延迟数据全靠这个积累。 */
    inline fun <T> timed(what: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        return try {
            block().also { i("$what ok ${System.currentTimeMillis() - t0}ms") }
        } catch (e: Throwable) {
            e("$what fail ${System.currentTimeMillis() - t0}ms", e)
            throw e
        }
    }

    private fun write(level: String, msg: String) {
        Log.println(if (level == "E") Log.ERROR else Log.INFO, TAG, msg)
        if (!::file.isInitialized) return
        synchronized(lock) {
            runCatching {
                // 滚动：超限就砍掉前半，保留近期
                if (file.length() > MAX) {
                    val keep = file.readText().let { it.substring(it.length / 2) }
                    file.writeText(keep.substringAfter('\n', keep))
                }
                file.appendText("${fmt.format(Date())} $level ${redact(msg)}\n")
            }
        }
    }

    /** 凭证绝不落盘。passToken/serviceToken/ssecurity 一旦进日志就等于泄露账号。 */
    private fun redact(s: String): String =
        s.replace(Regex("""(passToken|serviceToken|ssecurity|psecurity)=[^\s,;)]+""")) { "${it.groupValues[1]}=***" }
}
