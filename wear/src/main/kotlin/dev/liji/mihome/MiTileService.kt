package dev.liji.mihome

import android.content.ComponentName
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 抬腕、一点、灯亮。
 *
 * 三条硬约束决定了这个设计：
 *  1. Tile 是**静态快照**，在系统进程里渲染，点击只能触发 LoadAction（重绘）或
 *     LaunchAction（拉活动）——不能在 Tile 里跑任意代码，所以开关动作交给 ToggleActivity。
 *  2. onTileRequest 必须几秒内返回，否则内容会变陈旧。因此这里**只读 SharedPreferences 缓存**，
 *     绝不发网络请求。
 *  3. Wear OS 6 禁止 am-start 非 exported 的前台服务，透明的 exported Activity 是官方等价物。
 *
 * 做这个功能的前提已用实测满足：蓝牙链路下写一次开关 199–595ms，远低于 3 秒门槛。
 */
class MiTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val items = TileState.load(this)
        val layout = layout(items, requestParams.deviceConfiguration, TileState.isStale(this))
        return immediate(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                // 缓存最多存 10 分钟就让系统来要一次新的；真正的更新由 ToggleActivity 主动推
                .setFreshnessIntervalMillis(TimeUnit.MINUTES.toMillis(10))
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build(),
        )
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = immediate(
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
    )

    /**
     * 2 列 × 3 行，六个设备。
     *
     * 旧版是三条整宽长条，一条只放一个名字，横向空间几乎全废——和列表页当初一样的毛病。
     * 圆屏的内接正方形是 226/√2 ≈ 160dp，所以网格必须比它更瘦：
     * 70×58dp 的格子配 6dp 间距，整体 146×186dp，四角落在
     * √(57²+77²)+16 ≈ 111.8dp 处，刚好在 113dp 的半径内。再大一点就要被切角。
     */
    private fun layout(
        items: List<TileState.Item>,
        device: DeviceParametersBuilders.DeviceParameters,
        stale: Boolean,
    ): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()

        if (items.isEmpty()) {
            column.addContent(
                Text.Builder(this, getString(R.string.tile_login_first))
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(androidx.wear.protolayout.ColorBuilders.argb(Hyper.Muted.toArgb()))
                    .build(),
            )
        } else {
            items.take(GRID_COLS * GRID_ROWS).chunked(GRID_COLS).forEachIndexed { r, row ->
                if (r > 0) column.addContent(gap(GAP))
                val line = LayoutElementBuilders.Row.Builder()
                row.forEachIndexed { c, item ->
                    if (c > 0) line.addContent(hgap(GAP))
                    line.addContent(cell(item, device, stale))
                }
                // 落单的补个等宽空位，否则这一行会被居中排版拽偏
                if (row.size < GRID_COLS) {
                    line.addContent(hgap(GAP))
                    line.addContent(hgap(CELL_W))
                }
                column.addContent(line.build())
            }
        }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .addContent(column.build())
            .build()
    }

    /**
     * 一个格子：整块可点，颜色表达开关，名字最多两行。
     * [stale] 时名字下压一个淡点：状态取自 30 分钟前的缓存，可能已经不是现状。
     * 点它照常能开关（目标状态取反不依赖新旧），所以只提示、不拦。
     */
    private fun cell(
        item: TileState.Item,
        device: DeviceParametersBuilders.DeviceParameters,
        stale: Boolean,
    ): LayoutElementBuilders.LayoutElement {
        // 点击拉起透明的 ToggleActivity 执行写入——Tile 自身不能跑任意代码
        val click = ModifiersBuilders.Clickable.Builder()
            .setId(item.did)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(ToggleActivity::class.java.name)
                            .addKeyToExtraMapping(
                                ToggleActivity.EXTRA_DID,
                                ActionBuilders.AndroidStringExtra.Builder().setValue(item.did).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        // 开着＝设备身份色，关着/未知＝深灰，和主界面的磁贴同一套语言。
        // protolayout 不支持渐变，取渐变两端的中间色作近似。
        val on = item.on == true
        val bg = if (on) {
            val acc = accentOf(item.category)
            lerp(acc.light, acc.deep, 0.45f).toArgb()
        } else {
            Hyper.Surface.toArgb()
        }
        val fg = if (on) Hyper.OnAccent.toArgb() else Hyper.OnSurface.toArgb()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(CELL_W))
            .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(CELL_H))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(androidx.wear.protolayout.ColorBuilders.argb(bg))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(androidx.wear.protolayout.DimensionBuilders.dp(16f))
                                    .build(),
                            )
                            .build(),
                    )
                    .setClickable(click)
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(androidx.wear.protolayout.DimensionBuilders.dp(5f))
                            .setEnd(androidx.wear.protolayout.DimensionBuilders.dp(5f))
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(if (item.on == null) "${item.name} —" else item.name)
                            .setMaxLines(2)
                            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(androidx.wear.protolayout.DimensionBuilders.sp(11f))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                                    .setColor(androidx.wear.protolayout.ColorBuilders.argb(fg))
                                    .build(),
                            )
                            .build(),
                    )
                    .apply {
                        if (stale) {
                            addContent(gap(3f))
                            // protolayout 没有圆形图元，圆角撑满的小方块就是圆点
                            addContent(
                                LayoutElementBuilders.Box.Builder()
                                    .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(4f))
                                    .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(4f))
                                    .setModifiers(
                                        ModifiersBuilders.Modifiers.Builder()
                                            .setBackground(
                                                ModifiersBuilders.Background.Builder()
                                                    .setColor(
                                                        androidx.wear.protolayout.ColorBuilders.argb(
                                                            (if (on) Hyper.OnAccent else Hyper.Muted).copy(alpha = 0.55f).toArgb(),
                                                        ),
                                                    )
                                                    .setCorner(
                                                        ModifiersBuilders.Corner.Builder()
                                                            .setRadius(androidx.wear.protolayout.DimensionBuilders.dp(2f))
                                                            .build(),
                                                    )
                                                    .build(),
                                            )
                                            .build(),
                                    )
                                    .build(),
                            )
                        }
                    }
                    .build(),
            )
            .build()
    }

    private fun hgap(w: Float) = LayoutElementBuilders.Spacer.Builder()
        .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(w))
        .build()

    private fun gap(h: Float) = LayoutElementBuilders.Spacer.Builder()
        .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(h))
        .build()

    /**
     * onTileRequest 只读本地缓存，本来就是同步的，所以直接返回已完成的 Future。
     * 这样能省掉 guava/concurrent-futures 依赖——对只能走阿里云镜像的构建，
     * 少一个依赖就是少一个失败面。
     */
    private fun <T> immediate(value: T): ListenableFuture<T> = object : ListenableFuture<T> {
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = true
        override fun get(): T = value
        override fun get(timeout: Long, unit: TimeUnit): T = value
    }

    companion object {
        private const val RESOURCES_VERSION = "1"

        private const val GRID_COLS = 2
        private const val GRID_ROWS = 3
        private const val CELL_W = 70f
        private const val CELL_H = 58f
        private const val GAP = 6f
        /** 状态变了就让系统重绘 Tile。 */
        fun requestUpdate(ctx: android.content.Context) {
            runCatching {
                androidx.wear.tiles.TileService.getUpdater(ctx)
                    .requestUpdate(MiTileService::class.java)
            }.onFailure { Flog.w("请求 Tile 更新失败: ${it.message}") }
        }

        fun component(ctx: android.content.Context) =
            ComponentName(ctx, MiTileService::class.java)
    }
}
