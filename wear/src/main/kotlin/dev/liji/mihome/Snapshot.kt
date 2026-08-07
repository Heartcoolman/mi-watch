package dev.liji.mihome

import android.content.Context
import dev.liji.mihome.core.Control

/**
 * 上一次成功加载的设备快照。
 *
 * 冷启动要跑四段串行请求：gethome 1.1s → device_list ×N 0.9s → 拉 spec → prop/get 1.2s，
 * 合计三四秒，而且全部完成之前界面一片空白。抬腕开个灯要盯着空屏等三秒，
 * 比多按一下难受得多。
 *
 * 所以照 Tile 的老办法：把「上次看到的样子」存下来，开屏先画，再在后台刷新。
 * 存的东西刚好够让磁贴**可用**而不只是好看——带上电源的 siid/piid，
 * 于是缓存渲染出来的格子点一下就能开关，不必等真实数据回来。
 *
 * 格式是竖线分隔的行，不用 JSON：:wear 至今没有依赖 kotlinx-serialization，
 * 为一个八字段的表引一个序列化库不划算。
 */
object Snapshot {

    private const val KEY = "snapshot"
    private const val SEP = "" // 设备名里可能有竖线和逗号，用控制字符分隔最省事

    fun save(ctx: Context, devs: List<Dev>) {
        AndroidStore(ctx).set(
            KEY,
            devs.joinToString("\n") { d ->
                val p = d.power
                listOf(
                    d.did,
                    d.name,
                    d.model.orEmpty(),
                    d.category.orEmpty(),
                    d.room.orEmpty(),
                    when (d.on) { true -> "1"; false -> "0"; null -> "?" },
                    p?.siid?.toString().orEmpty(),
                    p?.piid?.toString().orEmpty(),
                    d.summaryText.orEmpty(),
                ).joinToString(SEP)
            },
        )
    }

    fun load(ctx: Context): List<Dev> =
        AndroidStore(ctx).get(KEY)?.split("\n").orEmpty().mapNotNull { row ->
            val f = row.split(SEP)
            if (f.size < 9 || f[0].isEmpty()) return@mapNotNull null
            val siid = f[6].toIntOrNull()
            val piid = f[7].toIntOrNull()
            val on = when (f[5]) { "1" -> true; "0" -> false; else -> null }

            // 用存下的 siid/piid 造一个电源开关：缓存渲染出来的磁贴因此**点一下就能开关**，
            // 不用等设备列表和 spec 回来。真实数据到达后整份替换掉。
            val power = if (siid != null && piid != null) {
                Control.Toggle(siid, piid, "开关", primary = true, quick = true, isPower = true)
            } else {
                null
            }
            Dev(
                did = f[0],
                name = f[1],
                online = true,
                category = f[3].ifEmpty { null },
                model = f[2].ifEmpty { null },
                room = f[4].ifEmpty { null },
                controls = listOfNotNull(power),
                values = if (power != null && on != null) {
                    mapOf((power.siid to power.piid) to DevValue(true, bool = on))
                } else {
                    emptyMap()
                },
                snapSummary = f[8].ifEmpty { null },
            )
        }
}
