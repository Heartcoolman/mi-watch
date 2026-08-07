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

        // 每次调试启动都记一遍当前网络，出问题时不用猜是走的哪条
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
