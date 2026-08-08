package dev.liji.mihome

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest

/**
 * 表盘 Complication：第一个收藏设备的开关状态，点一下就切。
 * 比 Tile 还快一层——不用滑，抬腕就在表盘上。
 *
 * 数据源直接读 [TileState]（第一格就是收藏区第一个可开关设备），零新增状态；
 * 点击复用 ToggleActivity，和 Tile 是同一条执行路径。
 * UPDATE_PERIOD_SECONDS 设 0：不让系统定时拉（那是耗电大户），
 * 状态变了由 App / ToggleActivity 主动推。
 */
class MiComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        listener.onComplicationData(build(TileState.load(this).firstOrNull()))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) {
            short(getString(R.string.on), getString(R.string.app_name), null)
        } else {
            null
        }

    private fun build(item: TileState.Item?): ComplicationData {
        if (item == null) return short("—", getString(R.string.app_name), null)
        val state = when (item.on) {
            true -> getString(R.string.on)
            false -> getString(R.string.off)
            null -> "—"
        }
        // requestCode 按 did 区分：PendingIntent 的身份不看 extras，固定 0 会让
        // 「收藏第一位换了设备」之后、还没刷新的表盘拿着旧标签切新设备
        val tap = PendingIntent.getActivity(
            this,
            item.did.hashCode(),
            Intent(this, ToggleActivity::class.java).putExtra(ToggleActivity.EXTRA_DID, item.did),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return short(state, item.name, tap)
    }

    private fun short(text: String, title: String, tap: PendingIntent?): ComplicationData =
        ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(title).build(),
        )
            .setTitle(PlainComplicationText.Builder(title).build())
            .setTapAction(tap)
            .build()

    companion object {
        /** 状态变了主动推一次。表盘没装这个 Complication 时是空操作，代价可忽略。 */
        fun requestUpdate(ctx: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester
                    .create(ctx, ComponentName(ctx, MiComplicationService::class.java))
                    .requestUpdateAll()
            }.onFailure { Flog.w("请求 Complication 更新失败: ${it.message}") }
        }
    }
}
