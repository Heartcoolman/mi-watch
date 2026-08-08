package dev.liji.mihome

import android.content.Context

/**
 * Tile 渲染用的「最后已知状态」。
 *
 * Tile 在系统进程里渲染，onTileRequest 必须几秒内返回，
 * 所以它**绝不能发网络请求**——只读这里的缓存，保证瞬间出图。
 * 真实数据由主界面和 ToggleActivity 写入。
 *
 * 连电源属性的 siid/piid 也一起存：这样 ToggleActivity 不必再拉一次 spec 和设备列表，
 * 点一下就只剩一个 prop/set 的往返。
 */
object TileState {

    private const val KEY = "tile_devices"
    private const val KEY_AT = "tile_devices_at"

    /** 距上次全量同步超过这个时长，Tile 上的状态就该标记为「可能过时」。 */
    private const val STALE_MS = 30L * 60 * 1000

    data class Item(
        val did: String,
        val name: String,
        val on: Boolean?,
        val siid: Int,
        val piid: Int,
        /** spec 类别，Tile 靠它取和主界面一致的身份色。 */
        val category: String? = null,
    )

    fun save(ctx: Context, items: List<Item>) {
        saveRows(ctx, items)
        // 只在全量同步时记时间。ToggleActivity 的单点 put 只证明一个设备是新的，
        // 拿它刷新整体时间戳会把其余五格的陈旧洗白
        AndroidStore(ctx).set(KEY_AT, System.currentTimeMillis().toString())
    }

    private fun saveRows(ctx: Context, items: List<Item>) {
        AndroidStore(ctx).set(
            KEY,
            items.joinToString(";") {
                "${it.did}|${it.name}|${it.on?.let { b -> if (b) "1" else "0" } ?: "?"}|${it.siid}|${it.piid}|${it.category.orEmpty()}"
            },
        )
    }

    /**
     * Tile 显示的状态是否已经太旧。真实新鲜度取决于上次开 App 的时间——
     * onTileRequest 不发网络（也不该发），所以「过时」只能标出来，不能自己修。
     */
    fun isStale(ctx: Context): Boolean {
        val at = AndroidStore(ctx).get(KEY_AT)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - at > STALE_MS
    }

    fun load(ctx: Context): List<Item> =
        AndroidStore(ctx).get(KEY)?.split(";").orEmpty().mapNotNull { row ->
            val f = row.split("|")
            // 宽松判长度：升级后旧格式（5 段、无 category）仍要能读，否则 Tile 会空到下次刷新
            if (f.size < 5) return@mapNotNull null
            Item(
                did = f[0],
                name = f[1],
                on = when (f[2]) { "1" -> true; "0" -> false; else -> null },
                siid = f[3].toIntOrNull() ?: return@mapNotNull null,
                piid = f[4].toIntOrNull() ?: return@mapNotNull null,
                category = f.getOrNull(5)?.ifEmpty { null },
            )
        }

    fun put(ctx: Context, did: String, on: Boolean?) {
        // 不走 save()：单点更新只证明这一个设备是新的，不该刷新全量同步时间戳
        saveRows(ctx, load(ctx).map { if (it.did == did) it.copy(on = on) else it })
    }
}
