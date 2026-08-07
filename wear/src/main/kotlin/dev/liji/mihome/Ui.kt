package dev.liji.mihome

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SplitSwitchButton
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import dev.liji.mihome.core.Control
import kotlin.math.roundToInt

@Composable
fun App(model: AppModel) {
    val s by model.state.collectAsStateWithLifecycle()
    MaterialTheme {
        when (val screen = s.screen) {
            is Screen.Loading -> Centered { CircularProgressIndicator() }
            is Screen.Login -> LoginScreen(screen, model)
            is Screen.Devices -> DeviceScreen(s, model)
            is Screen.Detail -> {
                BackHandler { model.back() }
                val dev = s.devices.firstOrNull { it.did == screen.did }
                if (dev == null) Centered { Text("设备不存在") } else DetailScreen(dev, s, model)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Screen(content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

// ---------- 登录 ----------

/**
 * 三件事决定手机能不能扫得动 33mm 圆屏上的码：强制最高亮度、纯黑白反转（不跟随深色主题）、
 * 静默区压到 2 个模块（在 qrBitmap 里）。表会激进自动调暗，OLED 低亮度下对比度直接毁掉。
 */
@Composable
private fun LoginScreen(screen: Screen.Login, model: AppModel) {
    val bmp = screen.qr
    if (bmp != null) {
        BrightAndAwake()
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
            Image(bmp.asImageBitmap(), contentDescription = "登录二维码", modifier = Modifier.size(170.dp))
        }
    } else {
        Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text(screen.hint, textAlign = TextAlign.Center)
                Button(onClick = { model.beginQrLogin() }, label = { Text("重新生成") })
            }
        }
    }
}

/** material3 的 KeepScreenOn 在 1.6.2 里是 internal，只能自己加窗口标志。 */
@Composable
private fun BrightAndAwake() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val window = (ctx as? Activity)?.window
        val prev = window?.attributes?.screenBrightness
        window?.let { w ->
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            window?.let { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                w.attributes = w.attributes.apply {
                    screenBrightness = prev ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }
}

// ---------- 设备列表 ----------

@Composable
private fun DeviceScreen(state: UiState, model: AppModel) {
    Screen {
        item { ListHeader { Text("米家") } }

        items(state.devices) { d ->
            // 分离式：点开关直接切换（抬腕开灯一步到位），点名字进详情调温度风速
            SplitSwitchButton(
                checked = d.on == true,
                onCheckedChange = { model.toggle(d.did) },
                toggleContentDescription = "开关",
                onContainerClick = { model.open(d.did) },
                enabled = d.power != null && d.on != null && !d.busy,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(d.name, maxLines = 1) },
                secondaryLabel = { Text(stateText(d)) },
            )
        }

        item {
            Button(
                onClick = { model.refresh() },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (state.busy) "刷新中…" else "刷新") },
            )
        }

        errorItem(state, model)
    }
}

private fun stateText(d: Dev) = when {
    d.power == null -> "不支持"
    d.busy -> "…"
    d.on == null -> "离线"
    d.on == true -> "开"
    else -> "关"
}

// ---------- 设备详情 ----------

@Composable
private fun DetailScreen(dev: Dev, state: UiState, model: AppModel) {
    Screen {
        item { ListHeader { Text(dev.name, maxLines = 1) } }

        dev.quick.forEach { c ->
            when (c) {
                is Control.Toggle -> item {
                    SwitchButton(
                        checked = dev.valueOf(c)?.bool == true,
                        onCheckedChange = { model.write(dev.did, c, DevValue(true, bool = it)) },
                        enabled = dev.valueOf(c)?.ok == true && !dev.busy,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(c.label, maxLines = 1) },
                    )
                }

                // 色温跨度 2700–6500，33mm 屏上连续调没法用，改成几个预设档
                is Control.Range -> if (c.isKelvin) choiceItems(dev, model, c.label, kelvinPresets(c), c)
                else item { StepRow(dev, model, c) }

                is Control.Choice -> choiceItems(dev, model, c.label, c.options, c)

                is Control.Readout -> item { ReadoutRow(dev, c) }
            }
        }

        item {
            Button(
                onClick = { model.open(dev.did) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (state.busy) "读取中…" else "刷新") },
            )
        }

        errorItem(state, model)
    }
}

/** 数值属性用 −/+ 步进：圆屏上比滑块好按，也不会误触。 */
@Composable
private fun StepRow(dev: Dev, model: AppModel, c: Control.Range) {
    val v = dev.valueOf(c)
    val cur = v?.num
    val enabled = v?.ok == true && !dev.busy
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(c.label, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(
                onClick = { cur?.let { model.write(dev.did, c, DevValue(true, num = c.stepped(it - c.step))) } },
                enabled = enabled && cur != null && cur > c.min,
                label = { Text("−") },
            )
            Text(
                text = cur?.let { fmt(it, c) } ?: "—",
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(
                onClick = { cur?.let { model.write(dev.did, c, DevValue(true, num = c.stepped(it + c.step))) } },
                enabled = enabled && cur != null && cur < c.max,
                label = { Text("+") },
            )
        }
    }
}

/** 枚举属性：每个选项一行，当前项高亮。比滚轮选择器好按得多。 */
private fun ScalingLazyListScope.choiceItems(
    dev: Dev,
    model: AppModel,
    label: String,
    options: List<Pair<Int, String>>,
    c: Control,
) {
    item { ListHeader { Text(label, maxLines = 1) } }
    val cur = dev.valueOf(c)?.num?.roundToInt()
    options.forEach { (value, text) ->
        item {
            val selected = cur == value
            val onClick = { model.write(dev.did, c, DevValue(true, num = value.toDouble())) }
            if (selected) {
                Button(
                    onClick = onClick,
                    enabled = !dev.busy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("● $text", maxLines = 1) },
                )
            } else {
                FilledTonalButton(
                    onClick = onClick,
                    enabled = !dev.busy,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun ReadoutRow(dev: Dev, c: Control.Readout) {
    val v = dev.valueOf(c)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(c.label, maxLines = 1, modifier = Modifier.width(90.dp))
        Text(
            if (v?.ok == true && v.num != null) trimNum(v.num) + (c.unit?.let { " " + shortUnit(it) } ?: "") else "—",
        )
    }
}

private fun kelvinPresets(c: Control.Range): List<Pair<Int, String>> =
    listOf(2700, 3500, 5000, 6500).filter { it >= c.min && it <= c.max }.map { it to "${it}K" }

private fun fmt(v: Double, c: Control.Range): String =
    trimNum(v) + (c.unit?.let { shortUnit(it) } ?: "")

private fun trimNum(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else String.format("%.1f", v)

private fun shortUnit(u: String) = when (u) {
    "percentage" -> "%"
    "celsius" -> "°"
    "kelvin" -> "K"
    "watt" -> "W"
    "lux" -> "lx"
    "minutes" -> "分"
    "seconds" -> "秒"
    else -> u
}

private fun ScalingLazyListScope.errorItem(state: UiState, model: AppModel) {
    state.error?.let { err ->
        item {
            Button(
                onClick = { model.dismissError() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(err, maxLines = 2, textAlign = TextAlign.Center) },
            )
        }
    }
}

/** foundation.lazy 的 scope 上没有直接吃 List 的 items 重载。 */
private inline fun <T> ScalingLazyListScope.items(
    list: List<T>,
    crossinline block: @Composable (T) -> Unit,
) = items(list.size) { block(list[it]) }
