package dev.liji.mihome

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 米家原生设备图标，按 model 取自 `assets/icon/<model>.png`。
 *
 * 由 `./mi icons wear/src/main/assets/icon` 在构建期抓好打进 APK——
 * 运行时一次网络请求都不发（原因见 :core 的 MiIcons）。
 *
 * 图标是产品实拍渲染、透明底近白色，所以深色卡片上直接贴就够，不需要染色；
 * 也因为是实拍，小尺寸会糊，磁贴上按 24dp 画（列表卡片曾用 34dp）。
 *
 * **解码不能在合成里同步做**：源图 168×168，一屏六七个磁贴各解一次，
 * 实测把首帧从「快照就绪」又推后了将近一秒。所以两件事一起做——
 * 按目标尺寸降采样，以及扔到 IO 线程去解，先画自绘图形、解好了再换上。
 */
object DeviceIcon {

    private val cache = HashMap<String, ImageBitmap?>()

    /** 已解码过的直接给，避免重进这一屏时图标又闪一下。 */
    fun peek(model: String?): ImageBitmap? = if (model == null) null else cache[model]

    fun load(ctx: Context, model: String?): ImageBitmap? {
        if (model.isNullOrEmpty()) return null
        synchronized(cache) { if (cache.containsKey(model)) return cache[model] }
        val bmp = runCatching {
            // 168px 的源图画到 24dp（≈51px）的格子里，降一半采样绰绰有余，
            // 像素工作量少四倍
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            ctx.assets.open("icon/$model.png").use { BitmapFactory.decodeStream(it, null, opts) }
                ?.asImageBitmap()
        }.onFailure { Flog.w("图标缺失 $model") }.getOrNull()
        synchronized(cache) { cache[model] = bmp }
        return bmp
    }

    /** 设备列表到手后在后台把图标全解出来，之后滚动就不会再有解码停顿。 */
    suspend fun prewarm(ctx: Context, models: List<String?>) = withContext(Dispatchers.IO) {
        models.filterNotNull().distinct().forEach { load(ctx, it) }
    }
}

/** 取不到就返回 null，调用方退回自绘的类别图形。 */
@Composable
fun rememberDeviceIcon(model: String?): ImageBitmap? {
    val ctx = LocalContext.current
    DeviceIcon.peek(model)?.let { return it }
    val bmp by produceState<ImageBitmap?>(null, model) {
        value = withContext(Dispatchers.IO) { DeviceIcon.load(ctx, model) }
    }
    return bmp
}
