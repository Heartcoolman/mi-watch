package dev.liji.mihome

import android.content.ComponentName
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
import androidx.wear.protolayout.material.layouts.PrimaryLayout
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
        val layout = layout(items, requestParams.deviceConfiguration)
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

    private fun layout(
        items: List<TileState.Item>,
        device: DeviceParametersBuilders.DeviceParameters,
    ): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())

        if (items.isEmpty()) {
            column.addContent(
                Text.Builder(this, "先打开米家 App 登录")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(androidx.wear.protolayout.ColorBuilders.argb(0xFFBBBBBB.toInt()))
                    .build(),
            )
        } else {
            items.take(3).forEachIndexed { i, item ->
                if (i > 0) column.addContent(spacer())
                column.addContent(chip(item, device))
            }
        }

        // 不用 PrimaryLayout：它的内容区留白太多，480×480 上三个 chip 第三个会被切掉。
        // 自己用 Box 居中 + 8dp 内边距，正好放得下。
        return LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    // 横向留白要比纵向大：圆屏会把整宽 chip 的两端切掉
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(androidx.wear.protolayout.DimensionBuilders.dp(22f))
                            .setEnd(androidx.wear.protolayout.DimensionBuilders.dp(22f))
                            .setTop(androidx.wear.protolayout.DimensionBuilders.dp(6f))
                            .setBottom(androidx.wear.protolayout.DimensionBuilders.dp(6f))
                            .build(),
                    )
                    .build(),
            )
            .addContent(column.build())
            .build()
    }

    private fun chip(
        item: TileState.Item,
        device: DeviceParametersBuilders.DeviceParameters,
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

        // 开着＝主色高亮，关着/未知＝次级色。
        // 不加副标题：三个 chip 各带一行副标题在 480×480 上放不下，第三个会被切掉。
        // 状态交给颜色表达——这也是 Wear 自带卡片的做法，顺带让点击目标更大。
        val colors = if (item.on == true) {
            ChipColors.primaryChipColors(TILE_THEME)
        } else {
            ChipColors.secondaryChipColors(TILE_THEME)
        }

        return Chip.Builder(this, click, device)
            .setPrimaryLabelContent(if (item.on == null) "${item.name} —" else item.name)
            .setChipColors(colors)
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .build()
    }

    private fun spacer() = LayoutElementBuilders.Spacer.Builder()
        .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(4f))
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
        private val TILE_THEME = androidx.wear.protolayout.material.Colors(
            0xFF4C8DFF.toInt(), // primary
            0xFF000000.toInt(), // onPrimary
            0xFF2A2E35.toInt(), // surface
            0xFFE6E6E6.toInt(), // onSurface
        )

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
