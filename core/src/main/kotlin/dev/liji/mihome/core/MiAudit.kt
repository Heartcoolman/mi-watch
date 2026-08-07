package dev.liji.mihome.core

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 拿 miot-spec.org 的真实语料检验 toControls 的覆盖率。
 *
 * 归约规则最初是照着一户人家的 21 个设备调出来的，样本太小：一条规则在这里对，
 * 换个品类可能整台设备零控件。开源意味着要面对 47098 个 instance、上百个品类，
 * 所以规则的好坏必须能被**测量**而不是靠感觉。
 *
 * 这个命令免登录（spec 服务器是公开的），抓下来的 spec 落盘缓存，重跑不花钱。
 */
object MiAudit {

    data class Row(
        val model: String,
        val category: String,
        val services: Int,
        val total: Int,
        val quick: Int,
        val hasPower: Boolean,
        val quickWritable: Int,
        val quickReadouts: Int,
    )

    fun run(cacheDir: File, perCategory: Int, threads: Int = 8): List<Row> {
        val client = SpecClient(cacheDir.apply { mkdirs() })
        val all = SpecClient.instances()
        println("instances 总数 ${all.size}")

        // 每个品类抽 perCategory 个 model，覆盖广度比覆盖深度重要：
        // 同一品类的不同型号差异远小于跨品类的差异
        val sample = all.filter { it.status == "released" }
            .groupBy { it.type.urnCategory() ?: "?" }
            .flatMap { (_, v) -> v.distinctBy { it.model }.take(perCategory) }
        println("抽样 ${sample.size} 个 model，覆盖 ${sample.mapNotNull { it.type.urnCategory() }.distinct().size} 个品类")

        val pool = Executors.newFixedThreadPool(threads)
        val done = AtomicInteger()
        val rows = java.util.Collections.synchronizedList(mutableListOf<Row>())
        val failed = java.util.Collections.synchronizedList(mutableListOf<String>())

        sample.forEach { inst ->
            pool.submit {
                runCatching {
                    val spec = client.spec(inst.type)
                    val cs = spec.toControls()
                    val q = cs.filter { it.quick }
                    rows += Row(
                        model = inst.model,
                        category = inst.type.urnCategory() ?: "?",
                        services = spec.services.size,
                        total = cs.size,
                        quick = q.size,
                        hasPower = q.any { it is Control.Toggle && it.isPower },
                        quickWritable = q.count { it !is Control.Readout },
                        quickReadouts = q.count { it is Control.Readout },
                    )
                }.onFailure { failed += inst.model }
                val n = done.incrementAndGet()
                if (n % 50 == 0) print("\r  已处理 $n/${sample.size}")
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.MINUTES)
        println("\r  完成 ${rows.size}，失败 ${failed.size}                ")
        if (failed.isNotEmpty()) println("  取不到 spec: ${failed.take(8).joinToString()}${if (failed.size > 8) " …" else ""}")
        return rows
    }
}
