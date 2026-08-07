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

        // 开发期兜底：adb shell am start -n dev.liji.mihome/.MainActivity --es session '<blob>'
        // 蓝牙代理万一走不完登录状态机，用桌面 harness 取到的会话直接注入。
        val imported = intent?.getStringExtra("session")?.let { model.importSession(it) } == true

        setContent { App(model) }

        if (!imported) model.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("session")?.let { model.importSession(it) }
    }
}
