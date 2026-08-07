package dev.liji.mihome.core

import java.io.File

/**
 * 米家原生设备图标的构建期抓取。
 *
 * 为什么在构建期而不是运行时：图标地址是
 * `https://cnbj1.fds.api.xiaomi.com/iotweb-product-center/<productId>.png`，
 * 而 **productId 拿不到官方来源**——`home_device_list` 不返回它（`pid` 恒为 0），
 * `miot-spec.org` 的 instances 列表只有 model/type/version。目前唯一的映射来自
 * 社区站 home.miot-spec.com 的产品页 HTML。
 *
 * 在表上跑这个既慢（蓝牙链路上多两跳）又脆（依赖第三方站点的 HTML 结构）。
 * 所以照 spec 的老办法：桌面上抓一次、写进 assets、APK 里带着走，运行时零网络。
 * 加了新设备就重跑一次 `./mi icons`。
 */
object MiIcons {

    private const val PRODUCT_PAGE = "https://home.miot-spec.com/spec/"
    private val ICON_URL = Regex("""https://cnbj1\.fds\.api\.xiaomi\.com/iotweb-product-center/\d+\.png""")

    private val client = MiHttp.client(readTimeoutSec = 30, callTimeoutSec = 60)

    /** 返回写入的文件数。取不到的型号跳过——图标缺失时 UI 会退回自绘图形。 */
    fun fetchInto(outDir: File, models: List<String>): Int {
        outDir.mkdirs()
        var ok = 0
        for (model in models.distinct()) {
            val dst = File(outDir, "$model.png")
            if (dst.isFile) {
                println("· $model 已存在，跳过")
                ok++
                continue
            }
            val url = runCatching { ICON_URL.find(MiHttp.getText(client, PRODUCT_PAGE + model))?.value }
                .getOrNull()
            if (url == null) {
                println("✗ $model 没找到图标")
                continue
            }
            val bytes = runCatching {
                client.newCall(MiHttp.req(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()
                }
            }.getOrNull()
            if (bytes == null) {
                println("✗ $model 下载失败 $url")
                continue
            }
            dst.writeBytes(bytes)
            println("✓ $model  ${bytes.size / 1024}KB  ${url.substringAfterLast('/')}")
            ok++
        }
        return ok
    }
}
