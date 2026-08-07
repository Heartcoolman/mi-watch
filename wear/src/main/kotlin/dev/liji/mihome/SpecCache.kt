package dev.liji.mihome

import android.content.Context
import dev.liji.mihome.core.SpecClient
import java.io.File

/**
 * 三级：内存（SpecClient 内部）→ filesDir → assets 预置 → 网络。
 *
 * assets 里那三份是 Stage 3 在 Mac 上抓好打包进来的。首启就展开到 filesDir，
 * 于是表上永远不会为了这三个设备去拉 spec——那是交互路径上最容易吃满
 * 蓝牙代理最坏延迟（实测研究给出 30 秒量级）的一步。
 */
object SpecCache {
    fun client(ctx: Context): SpecClient {
        val dir = File(ctx.filesDir, "spec")
        if (!dir.exists()) {
            dir.mkdirs()
            runCatching {
                val names = ctx.assets.list("spec").orEmpty()
                names.forEach { name ->
                    ctx.assets.open("spec/$name").use { input ->
                        File(dir, name).outputStream().use { input.copyTo(it) }
                    }
                }
                Flog.i("spec 预置展开 ${names.size} 个文件")
            }.onFailure { Flog.e("spec 预置展开失败", it) }
        }
        return SpecClient(dir)
    }
}
