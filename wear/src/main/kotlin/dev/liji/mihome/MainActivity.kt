package dev.liji.mihome

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {

    private lateinit var model: AppModel

    /**
     * 调试入口接管过启动流程。接管后不再让 onResume 自己去刷新——
     * `--es toggle` 这类命令自带一条加载路径，再并一个 refresh 只会打架。
     */
    private var debugTakeover = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Flog.init(this)
        Flog.i("=== onCreate v${BuildConfig.VERSION_NAME} ===")

        model = AppModel(applicationContext)

        setContent { App(model) }

        debugTakeover = handleDebugIntent(intent)
        if (!debugTakeover) model.start()
    }

    /**
     * 抬腕回到 App 时重新拉一次状态。进程活着的话这里是唯一的入口——
     * onCreate 只在冷启动跑一次，而设备状态在这中间早就被别处改过了。
     */
    override fun onResume() {
        super.onResume()
        if (!debugTakeover) model.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugTakeover = handleDebugIntent(intent)
    }

    /**
     * 开发期调试钩子，全部经 adb 触发：
     *   --es session '<blob>'  注入桌面 harness 取到的会话（登录走不完时的兜底）
     *   --es toggle '<did>'    立刻切换某设备——蓝牙盲测时唯一能远程发起一次真实控制的手段
     *
     * 返回 true 表示已接管启动流程，不再走常规 start()。
     */
    private fun handleDebugIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        // 只有调试入口才记网络快照。它要遍历所有网络查能力，
        // 放在每次冷启动的主线程上纯属白付。
        if (intent.extras?.isEmpty != false) return false
        Net.dumpNetworks(applicationContext)

        intent.getStringExtra("session")?.let {
            Flog.i("intent: 注入会话")
            return model.importSession(it)
        }

        intent.getStringExtra("toggle")?.let { did ->
            Flog.i("intent: 请求切换 $did")
            model.startThenToggle(did)
            return true
        }

        // --es resetfav 1：清掉收藏重新自动挑选，用来验证首次启动的默认值
        if (intent.getStringExtra("resetfav") != null) {
            Flog.i("intent: 重置收藏")
            model.resetFavorites()
            return true
        }

        // --es open <did>：直接进详情页，供截图核对渲染效果（省得靠猜坐标点击）
        intent.getStringExtra("open")?.let { did ->
            Flog.i("intent: 打开详情 $did")
            model.startThenOpen(did)
            return true
        }
        return false
    }
}
