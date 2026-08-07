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
 * Tile 的动作载体：透明、无界面、点完即退。
 *
 * 为什么要有它：Tile 是静态快照，点击只能触发 LoadAction 或 LaunchAction，
 * 不能在 Tile 里跑任意代码。而 Wear OS 6 又禁止 am-start 非 exported 的前台服务，
 * 所以透明的 exported Activity 是官方认可的等价物。
 *
 * 目标状态取自 Tile 上显示的缓存值取反——用户是照着屏幕上看到的状态按的，
 * 这比再花一个往返去读一次「真实」状态更符合直觉，也更快。
 */
class ToggleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Flog.init(applicationContext)

        val did = intent?.getStringExtra(EXTRA_DID)
        if (did == null) {
            finish()
            return
        }

        val item = TileState.load(this).firstOrNull { it.did == did }
        if (item == null) {
            Flog.w("Tile 点击 $did：缓存里没有这个设备")
            finish()
            return
        }

        val target = !(item.on ?: false)
        // 先乐观改缓存并重绘，用户抬手时 Tile 已经是新状态了
        TileState.put(this, did, target)
        MiTileService.requestUpdate(this)

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val api = MiApi(AndroidStore(this@ToggleActivity), MiAuth(AndroidStore(this@ToggleActivity)))
                    Flog.timed("tile set ${item.name} ${item.siid}.${item.piid}=$target") {
                        api.setBool(did, item.siid, item.piid, target)
                    }
                }
            }.onSuccess { ok ->
                if (!ok) {
                    Flog.w("Tile 写入被拒绝 $did，回滚缓存")
                    TileState.put(this@ToggleActivity, did, item.on)
                    MiTileService.requestUpdate(this@ToggleActivity)
                }
            }.onFailure { e ->
                Flog.e("Tile 写入失败 $did", e)
                TileState.put(this@ToggleActivity, did, item.on)
                MiTileService.requestUpdate(this@ToggleActivity)
            }
            // 做完再退：过早 finish 有被系统回收进程、请求半路夭折的风险。
            // 界面是透明的，用户看不到这一秒。
            finish()
        }
    }

    companion object {
        const val EXTRA_DID = "did"
    }
}
