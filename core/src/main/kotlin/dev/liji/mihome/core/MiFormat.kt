package dev.liji.mihome.core

/**
 * 属性值渲染。放在 :core 而不是 :wear，是为了让桌面 CLI 和手表显示**同一份结果**——
 * 否则 `./mi list` 打出来的东西只是「大概像」表上的样子，验证就失去意义了。
 */

/** spec 的单位名是英文全称，33mm 屏上放不下，全部压成符号。 */
fun shortUnit(u: String?): String = when (u) {
    "percentage" -> "%"
    "celsius" -> "°"
    "kelvin" -> "K"
    "watt" -> "W"
    "lux" -> "lx"
    "minutes" -> "分"
    "seconds" -> "秒"
    "hours" -> "时"
    "arcdegrees" -> "°"
    "none", null -> ""
    else -> u
}

fun trimNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format("%.1f", v)

/** 路由器的速率是裸字节数，482291 在 82dp 宽的 chip 上放不下。 */
fun bigNum(v: Double): String = when {
    v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000)
    v >= 10_000 -> String.format("%.0fk", v / 1000)
    else -> trimNum(v)
}

/**
 * 只读量渲染成人话，四件事按顺序做：布尔、枚举查表、有人无人特判、大数字缩写。
 *
 * 特判 occupancy-status 是因为这类属性常常没有 value-list，spec 描述里直接写着
 * 「0为无人，非0为有人」——照直显示就成了「有人无人 8」，看不懂。
 */
fun Control.Readout.render(num: Double?, bool: Boolean? = null): String {
    bool?.let { return if (it) "是" else "否" }
    val n = num ?: return "—"
    options.firstOrNull { it.first == Math.round(n).toInt() }?.let { return it.second }
    if (cat == "occupancy-status") return if (n != 0.0) "有人" else "无人"
    return bigNum(n) + shortUnit(unit)
}

/** 数值控件（滑块/步进）显示成「27°」「100%」。 */
fun Control.Range.render(v: Double): String = trimNum(v) + shortUnit(unit)

/**
 * spec 的中文描述有两种脏东西：非主服务的控件带「风机控制 · 」前缀，
 * 有些属性名后面还挂括号说明（「有人无人（0为无人，1为有人）」）。82dp 宽的 chip 两种都放不下。
 */
fun String.shortLabel(): String = substringAfterLast(" · ")
    .substringBefore("（")
    .substringBefore("(")
    .trim()
