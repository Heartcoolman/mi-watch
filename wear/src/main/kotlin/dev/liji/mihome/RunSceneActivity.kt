package dev.liji.mihome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.liji.mihome.core.MiApi
import dev.liji.mihome.core.MiAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 场景 Tile 的动作载体：透明、无界面、跑完即退。存在的理由同 [ToggleActivity]——
 * Tile 里不能跑任意代码，透明 exported Activity 是官方等价物。
 *
 * 不复用 ToggleActivity：它的语义是「按缓存状态取反再写回」，场景没有状态、
 * 没有乐观更新、没有回滚，混进去两边都变糊。
 */
class RunSceneActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Flog.init(applicationContext)

        val id = intent?.getStringExtra(EXTRA_SCENE_ID)
        if (id == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val api = MiApi(AndroidStore(this@RunSceneActivity), MiAuth(AndroidStore(this@RunSceneActivity)))
                    Flog.timed("tile scene $id") { api.runScene(id) }
                }
            }.onSuccess { ok ->
                if (!ok) Flog.w("Tile 场景被拒绝 $id")
            }.onFailure { e ->
                Flog.e("Tile 场景失败 $id", e)
            }
            // 做完再退：过早 finish 有被系统回收进程、请求半路夭折的风险
            finish()
        }
    }

    companion object {
        const val EXTRA_SCENE_ID = "scene"
    }
}
