package dev.liji.mihome

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * 米家原生设备图标，按 model 取自 `assets/icon/<model>.png`。
 *
 * 由 `./mi icons wear/src/main/assets/icon` 在构建期抓好打进 APK——
 * 运行时一次网络请求都不发（原因见 :core 的 MiIcons）。
 *
 * 图标是产品实拍渲染、透明底近白色，所以深色卡片上直接贴就够，不需要染色；
 * 也因为是实拍，小尺寸会糊，卡片上按 34dp 画而不是原来自绘图形的 21dp。
 */
object DeviceIcon {

    private val cache = HashMap<String, ImageBitmap?>()

    fun load(ctx: Context, model: String?): ImageBitmap? {
        if (model.isNullOrEmpty()) return null
        cache[model]?.let { return it }
        val bmp = runCatching {
            ctx.assets.open("icon/$model.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.onFailure { Flog.w("图标缺失 $model") }.getOrNull()
        cache[model] = bmp
        return bmp
    }
}

/** 取不到就返回 null，调用方退回自绘的类别图形。 */
@Composable
fun rememberDeviceIcon(model: String?): ImageBitmap? {
    val ctx = LocalContext.current
    return remember(model) { DeviceIcon.load(ctx, model) }
}
