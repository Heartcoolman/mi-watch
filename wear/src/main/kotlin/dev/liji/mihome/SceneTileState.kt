package dev.liji.mihome

import android.content.Context

/**
 * 场景 Tile 渲染用的缓存，同时兼作场景的启动快照（AppModel 初始状态直接读它）。
 *
 * 和 [TileState] 同一套约束：Tile 在系统进程里渲染，onTileRequest 绝不能发网络，
 * 只读这里；数据由主界面刷新时写入。场景没有开关状态，所以每格只有 id 和 name。
 */
object SceneTileState {

    private const val KEY = "tile_scenes"

    // 场景名是用户在米家里的自由文本，「回家|全开」这种名字用 ; | 分隔会被切碎。
    // 和 Snapshot 同一招：控制字符当分隔符，正常文本里打不出来
    private const val ROW = ""
    private const val FIELD = ""

    data class Item(val id: String, val name: String)

    fun save(ctx: Context, items: List<Item>) {
        AndroidStore(ctx).set(
            KEY,
            items.joinToString(ROW) { "${it.id}$FIELD${it.name}" },
        )
    }

    fun load(ctx: Context): List<Item> =
        AndroidStore(ctx).get(KEY)?.split(ROW).orEmpty().mapNotNull { row ->
            val f = row.split(FIELD)
            if (f.size < 2 || f[0].isEmpty()) return@mapNotNull null
            Item(id = f[0], name = f[1])
        }
}
