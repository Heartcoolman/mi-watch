package dev.liji.mihome

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {

    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Flog.init(this)
        Flog.i("=== onCreate v${BuildConfig.VERSION_NAME} ===")

        model = AppModel(applicationContext)

        setContent { App(model) }

        if (!handleDebugIntent(intent)) model.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
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

        Net.dumpNetworks(applicationContext)

        // --es net bt：把进程粘到蓝牙代理，验证「只走蓝牙」这条路。
        // 关键是不动 Wi-Fi——关 Wi-Fi 会连带关掉无线调试且不自动恢复，adb 就回不来了。
        if (intent.getStringExtra("net") == "bt") {
            val ok = Net.bindBluetooth(applicationContext)
            Flog.i("intent: 强制走蓝牙 -> ${if (ok) "已绑定" else "绑定失败，仍走默认网络"}")
        }

        intent.getStringExtra("session")?.let {
            Flog.i("intent: 注入会话")
            return model.importSession(it)
        }

        intent.getStringExtra("toggle")?.let { did ->
            Flog.i("intent: 请求切换 $did")
            model.startThenToggle(did)
            return true
        }
        return false
    }
}
