package dev.liji.mihome

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text

@Composable
fun App(model: AppModel) {
    val s by model.state.collectAsStateWithLifecycle()
    MaterialTheme {
        when (val screen = s.screen) {
            is Screen.Loading -> Centered { CircularProgressIndicator() }
            is Screen.Login -> LoginScreen(screen, model)
            is Screen.Devices -> DeviceScreen(s, model)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/**
 * 扫码屏。三件事决定手机能不能扫得动 33mm 圆屏上的码：
 * 强制最高亮度、纯黑白反转（不跟随深色主题）、静默区压到 2 个模块（在 qrBitmap 里）。
 * 表会激进自动调暗，OLED 低亮度下二维码对比度直接毁掉——亮度是这里价值最高的一招。
 */
@Composable
private fun LoginScreen(screen: Screen.Login, model: AppModel) {
    val bmp = screen.qr
    if (bmp != null) {
        BrightAndAwake()
        Box(
            Modifier.fillMaxSize().background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
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

/**
 * 扫码期间锁定最高亮度并防息屏，退出这一屏自动还原。
 * （material3 的 KeepScreenOn 在 1.6.2 里是 internal，只能自己加窗口标志。）
 */
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

@Composable
private fun DeviceScreen(state: UiState, model: AppModel) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { ListHeader { Text("米家") } }

        items(state.devices) { d ->
            SwitchButton(
                checked = d.on == true,
                onCheckedChange = { model.toggle(d.did) },
                // 逐项 code 非 0 或设备离线时禁用，不让陈旧值看起来像可用状态
                enabled = d.power != null && d.on != null && !d.busy,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(d.name, maxLines = 1) },
                secondaryLabel = {
                    Text(
                        when {
                            d.power == null -> "不支持"
                            d.busy -> "…"
                            d.on == null -> "离线"
                            d.on -> "开"
                            else -> "关"
                        },
                    )
                },
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

        state.error?.let { err ->
            item {
                Text(
                    err,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
    }
}

/** ScalingLazyColumn 的 items 扩展（foundation.lazy 的 scope 上没有直接吃 List 的重载）。 */
private inline fun <T> androidx.wear.compose.foundation.lazy.ScalingLazyListScope.items(
    list: List<T>,
    crossinline block: @Composable (T) -> Unit,
) = items(list.size) { block(list[it]) }
