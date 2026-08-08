package dev.liji.mihome

import androidx.compose.ui.graphics.toArgb
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 场景 Tile：抬腕、一点、一串设备各就各位。
 *
 * 结构与 [MiTileService] 相同（约束也相同：静态快照、只读缓存、动作交给透明 Activity）。
 * 不并进设备 Tile 是刻意的：Wear OS 允许一个 App 注册多个 Tile，用户自己决定
 * 装哪个、放哪页——比在一个 Tile 里塞两种语义好。
 *
 * 场景没有开关状态，格子不表达颜色，只是名字 + 统一的深灰底。
 */
class SceneTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val items = SceneTileState.load(this)
        return immediate(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(TimeUnit.MINUTES.toMillis(10))
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(items)))
                .build(),
        )
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = immediate(
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
    )

    private fun layout(items: List<SceneTileState.Item>): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()

        if (items.isEmpty()) {
            column.addContent(
                Text.Builder(this, getString(R.string.tile_no_scenes))
                    .setMaxLines(2)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(ColorBuilders.argb(Hyper.Muted.toArgb()))
                    .build(),
            )
        } else {
            items.take(GRID_COLS * GRID_ROWS).chunked(GRID_COLS).forEachIndexed { r, row ->
                if (r > 0) column.addContent(gap(GAP))
                val line = LayoutElementBuilders.Row.Builder()
                row.forEachIndexed { c, item ->
                    if (c > 0) line.addContent(hgap(GAP))
                    line.addContent(cell(item))
                }
                if (row.size < GRID_COLS) {
                    line.addContent(hgap(GAP))
                    line.addContent(hgap(CELL_W))
                }
                column.addContent(line.build())
            }
        }

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .addContent(column.build())
            .build()
    }

    private fun cell(item: SceneTileState.Item): LayoutElementBuilders.LayoutElement {
        val click = ModifiersBuilders.Clickable.Builder()
            .setId(item.id)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(RunSceneActivity::class.java.name)
                            .addKeyToExtraMapping(
                                RunSceneActivity.EXTRA_SCENE_ID,
                                ActionBuilders.AndroidStringExtra.Builder().setValue(item.id).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(CELL_W))
            .setHeight(DimensionBuilders.dp(CELL_H))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(Hyper.Surface.toArgb()))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(DimensionBuilders.dp(16f))
                                    .build(),
                            )
                            .build(),
                    )
                    .setClickable(click)
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(5f))
                            .setEnd(DimensionBuilders.dp(5f))
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(item.name)
                    .setMaxLines(2)
                    .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(DimensionBuilders.sp(11f))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_MEDIUM)
                            .setColor(ColorBuilders.argb(Hyper.OnSurface.toArgb()))
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun hgap(w: Float) = LayoutElementBuilders.Spacer.Builder()
        .setWidth(DimensionBuilders.dp(w))
        .build()

    private fun gap(h: Float) = LayoutElementBuilders.Spacer.Builder()
        .setHeight(DimensionBuilders.dp(h))
        .build()

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

        fun requestUpdate(ctx: android.content.Context) {
            runCatching {
                TileService.getUpdater(ctx).requestUpdate(SceneTileService::class.java)
            }.onFailure { Flog.w("请求场景 Tile 更新失败: ${it.message}") }
        }
    }
}
