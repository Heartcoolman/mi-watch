package dev.liji.mihome

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.liji.mihome.core.Control
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun App(model: AppModel) {
    val s by model.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Hyper.Bg)) {
            when (val screen = s.screen) {
                is Screen.Loading -> Centered { CircularProgressIndicator() }
                is Screen.Login -> LoginScreen(screen, model)
                is Screen.Devices -> DeviceScreen(s, model)
                is Screen.Detail -> {
                    val dev = s.devices.firstOrNull { it.did == screen.did }
                    if (dev == null) Centered { Text("设备不存在", color = Hyper.Muted) }
                    else DetailScreen(dev, model)
                }
            }
            ErrorToast(s, model)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Text(
                    screen.hint,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    color = Hyper.OnSurface,
                )
                Pill(
                    text = "重新生成",
                    accent = accentOf(null),
                    filled = true,
                    onClick = { model.beginQrLogin() },
                )
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

/**
 * 三张卡片在 226dp 上居中排布，静止时零滚动——「抬腕就能按」是这一页的全部目的。
 * 刷新/退出登录挤在下方，滚一下才露出来：它们一天用不到一次，不该占用最好的位置。
 */
@Composable
private fun DeviceScreen(state: UiState, model: AppModel) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 静止时让卡片组正好居中：(226 − 3×52 − 2×8) / 2
        Spacer(Modifier.height(27.dp))

        state.devices.forEachIndexed { i, d ->
            if (i > 0) Spacer(Modifier.height(Dim.CardGap))
            DeviceCard(d, onToggle = { model.toggle(d.did) }, onOpen = { model.open(d.did) })
        }

        if (state.devices.isEmpty()) {
            Text(
                if (state.busy) "载入中…" else "没有设备",
                color = Hyper.Muted,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(
                text = if (state.busy) "刷新中" else "刷新",
                accent = accentOf(null),
                filled = false,
                onClick = { model.refresh() },
            )
            Pill(text = "退出", accent = accentOf(null), filled = false, onClick = { model.signOut() })
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * HyperOS 控制中心的磁贴：开启态整块被身份色渐变填满，关闭态退回深灰。
 * 状态由颜色表达，不写「开/关」二字——一眼扫过去比读字快。
 *
 * 整卡点击＝开关。命中区从一个 40dp 的拨杆扩大到 174×52dp，抬腕时几乎点不歪；
 * 进详情改用长按，因为那是低频动作。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCard(d: Dev, onToggle: () -> Unit, onOpen: () -> Unit) {
    val acc = accentOf(d.category)
    val on = d.on == true
    val live = d.power != null && d.on != null
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }

    val fill by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "cardFill",
    )
    val fg by animateColorAsState(
        targetValue = if (on) Hyper.OnAccent else Hyper.OnSurface,
        label = "cardFg",
    )

    Box(
        modifier = Modifier
            .padding(horizontal = Dim.CardPad)
            .fillMaxWidth()
            .height(Dim.CardH)
            .pressScale(interaction)
            .clip(RoundedCornerShape(Dim.CardRadius))
            .background(Hyper.Surface)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = live && !d.busy,
                onClick = onToggle,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpen()
                },
            ),
    ) {
        // 渐变铺在底色之上，用 alpha 过渡，开关切换才是渐变而不是硬切
        Box(Modifier.fillMaxSize().alpha(fill).background(acc.horizontal))

        Row(
            modifier = Modifier.fillMaxSize().padding(start = 11.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 米家原生图标优先；抓不到该型号时退回自绘的类别图形
            val icon = rememberDeviceIcon(d.model)
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(34.dp))
            } else {
                Canvas(Modifier.size(21.dp).padding(end = 6.dp)) {
                    deviceGlyph(d.category, if (on) Hyper.OnAccent else acc.deep)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.name,
                    color = fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 正常状态不写字——颜色已经说完了。只有异常才需要解释
                offlineNote(d)?.let {
                    Text(it, color = if (on) Hyper.OnAccent else Hyper.Muted, fontSize = 10.sp, maxLines = 1)
                }
            }
            if (d.busy) {
                Box(
                    Modifier.size(6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (on) Hyper.OnAccent else Hyper.Muted),
                )
            }
        }
    }
}

private fun offlineNote(d: Dev): String? = when {
    d.power == null -> "不支持开关"
    d.on == null -> "离线"
    else -> null
}

// ---------- 设备详情 ----------

/**
 * 控制中心的展开面板：左边一根竖滑块承载最常调的那个量（灯＝亮度，空调＝温度），
 * 右边一列 chip 放模式/风速这些「选一个」的属性。
 *
 * 这样排的理由是使用频次：拖一下就能把亮度从 30 调到 80，而旧版的 −/+ 步进要点 50 次；
 * 模式一天换不了一次，收进 chip 后面完全不亏。
 */
@Composable
private fun DetailScreen(dev: Dev, model: AppModel) {
    val acc = accentOf(dev.category)
    val hero = heroRange(dev)
    var picking by remember { mutableStateOf<Control?>(null) }

    BackHandler { if (picking != null) picking = null else model.back() }

    Box(Modifier.fillMaxSize()) {
        Text(
            dev.name,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 40.dp, end = 40.dp),
            color = if (dev.busy) acc.light else Hyper.Muted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroSlider(
                dev = dev,
                range = hero,
                accent = acc,
                onToggle = { model.toggle(dev.did) },
                onCommit = { v -> hero?.let { model.write(dev.did, it, DevValue(true, num = v)) } },
            )
            Spacer(Modifier.width(Dim.ColGap))
            SideColumn(dev, acc, hero, model) { picking = it }
        }

        // 只读量走底部状态行而不是右列 chip：它们点不动，不该跟模式/风速抢那三个可见槽位。
        // 空调的电功率尤其值得一直看得见——2.5W 待机和 1660W 运行是「到底制没制冷」的唯一凭据。
        readoutLine(dev)?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 15.dp, start = 46.dp, end = 46.dp),
                color = Hyper.Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }

    picking?.let { c ->
        PickerOverlay(dev, c, acc, onPick = { v ->
            model.write(dev.did, c, DevValue(true, num = v.toDouble()))
            picking = null
        }, onDismiss = { picking = null })
    }
}

/** 主控件＝主服务里第一个连续量。色温不算：跨度 2700–6500，滑起来没有可用精度。 */
private fun heroRange(d: Dev): Control.Range? =
    d.quick.filterIsInstance<Control.Range>().firstOrNull { !it.isKelvin && it.primary }

/**
 * 竖滑块：拖动调值，点击开关。
 *
 * 一次拖动只发一次写请求——蓝牙链路上一次 prop/set 要 200–600ms，
 * 按像素发会把链路打满，而且中间那些值用户根本没想设。
 */
@Composable
private fun HeroSlider(
    dev: Dev,
    range: Control.Range?,
    accent: Accent,
    onToggle: () -> Unit,
    onCommit: (Double) -> Unit,
) {
    val on = dev.on == true
    val live = dev.power != null && dev.on != null
    val haptics = LocalHapticFeedback.current
    val committed = range?.let { dev.valueOf(it)?.num }
    var dragging by remember { mutableStateOf<Double?>(null) }
    val shown = dragging ?: committed

    val target = if (range != null && shown != null) {
        ((shown - range.min) / (range.max - range.min)).toFloat().coerceIn(0f, 1f)
    } else if (on) 1f else 0f
    // 拖动时要跟手，所以弹簧刚度拉高；松手后的回读修正也用同一条曲线，观感一致
    val frac by animateFloatAsState(target, spring(stiffness = Spring.StiffnessHigh), label = "fill")

    val fillAlpha by animateFloatAsState(if (on) 1f else 0.45f, label = "fillAlpha")

    Box(
        modifier = Modifier
            .width(Dim.HeroW)
            .height(Dim.HeroH)
            .clip(RoundedCornerShape(Dim.HeroRadius))
            .background(Hyper.SurfaceHi)
            .pointerInput(range, live, committed) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!live) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val start = committed ?: range?.min ?: 0.0
                    var dy = 0f
                    var moved = false
                    var cur = start
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        dy += ch.positionChange().y
                        if (!moved && abs(dy) > slop) moved = true
                        if (moved && range != null) {
                            ch.consume()
                            // 满高＝满量程；向上拖是加，所以取负
                            val span = range.max - range.min
                            cur = range.stepped(start + (-dy / size.height) * span)
                            dragging = cur
                        }
                    }
                    if (moved && range != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCommit(cur)
                        dragging = null
                    } else if (!moved) {
                        onToggle()
                    }
                }
            },
    ) {
        // 从底部长上来的填充。关闭态换成灰渐变——档位仍然可见，但不发光
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(frac.coerceAtLeast(0.001f))
                .alpha(fillAlpha)
                .background(if (on) accent.vertical else Hyper.DimFill),
        )

        // 顶部压一层暗渐变。填充高度是随值变的，数字有时落在深槽上、有时落在渐变最亮的一端；
        // 没有这层的话 100% 时白字压在 #FFD08A 上几乎看不见。
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(58.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(top = 15.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (range != null && shown != null) heroText(shown, range) else if (on) "开" else "关",
                color = Color.White,
                fontSize = if (range != null) 25.sp else 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // 电源符号放在滑块底部，兼作「点这块能开关」的提示——纯手势没有可发现性
            Canvas(Modifier.size(17.dp)) {
                powerGlyph(if (on) Hyper.OnAccent else Hyper.Muted)
            }
        }
    }
}

private fun heroText(v: Double, c: Control.Range): String =
    trimNum(v) + (c.unit?.let { shortUnit(it) } ?: "")

/**
 * 右列：模式/风速这类「选一个」的属性各占一个 chip，点开才弹选择层。
 * 一屏放得下三个，第四个之后要滚——但排在前面的都是常调的。
 */
@Composable
private fun SideColumn(
    dev: Dev,
    acc: Accent,
    hero: Control.Range?,
    model: AppModel,
    onPick: (Control) -> Unit,
) {
    val scroll = rememberScrollState()
    // 主开关已经并进滑块的点击，不再单列一项
    val rest = dev.quick.filter { it != hero && !(it is Control.Toggle && it.isPower) }

    Column(
        modifier = Modifier.width(Dim.ChipW).height(Dim.HeroH).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(Dim.ChipGap),
    ) {
        rest.forEach { c ->
            when (c) {
                is Control.Choice -> Chip(
                    label = shortLabel(c.label),
                    value = c.options.firstOrNull { it.first == dev.valueOf(c)?.num?.roundToInt() }?.second ?: "—",
                    accent = acc, active = false, onClick = { onPick(c) },
                )

                is Control.Range -> Chip(
                    label = shortLabel(c.label),
                    value = dev.valueOf(c)?.num?.let { heroText(it, c) } ?: "—",
                    accent = acc, active = false, onClick = { onPick(c) },
                )

                // 摆风这类开关直接点切，没必要再弹一层
                is Control.Toggle -> {
                    val v = dev.valueOf(c)?.bool == true
                    Chip(
                        label = shortLabel(c.label), value = if (v) "开" else "关",
                        accent = acc, active = v,
                        onClick = { model.write(dev.did, c, DevValue(true, bool = !v)) },
                    )
                }

                is Control.Readout -> Unit // 见 readoutLine()
            }
        }
    }
}

/** 底部状态行：把所有只读量压成一行「标签 值」。 */
private fun readoutLine(dev: Dev): String? = dev.quick
    .filterIsInstance<Control.Readout>()
    .mapNotNull { c ->
        val v = dev.valueOf(c) ?: return@mapNotNull null
        if (!v.ok || v.num == null) return@mapNotNull null
        shortLabel(c.label) + " " + trimNum(v.num) + (c.unit?.let { shortUnit(it) } ?: "")
    }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" · ")

/**
 * spec 的中文描述有两种脏东西：非主服务的控件带「风机控制 · 」前缀，
 * 有些属性名后面还挂着括号说明（「有人无人（0为无人，1为有人）」）。
 * 82dp 宽的 chip 两种都放不下。
 */
private fun shortLabel(s: String) = s
    .substringAfterLast(" · ")
    .substringBefore("（")
    .substringBefore("(")
    .trim()

@Composable
private fun Chip(
    label: String,
    value: String,
    accent: Accent,
    active: Boolean,
    onClick: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val fill by animateFloatAsState(if (active) 1f else 0f, label = "chipFill")
    val fg by animateColorAsState(if (active) Hyper.OnAccent else Hyper.OnSurface, label = "chipFg")

    Box(
        modifier = Modifier
            .width(Dim.ChipW)
            .height(Dim.ChipH)
            .pressScale(interaction)
            .clip(RoundedCornerShape(Dim.ChipRadius))
            .background(Hyper.SurfaceHi)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().alpha(fill).background(accent.horizontal))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                color = if (active) Hyper.OnAccent else Hyper.Muted,
                fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                color = fg,
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------- 选择层 ----------

/**
 * 盖在详情页上的全屏选择器。选完即关，一次点击完成「选哪个」这件事。
 * 这里允许滚动——换模式一天不到一次，为它牺牲主界面的空间不划算。
 */
@Composable
private fun PickerOverlay(
    dev: Dev,
    c: Control,
    acc: Accent,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = when (c) {
        is Control.Choice -> c.options
        is Control.Range -> kelvinPresets(c)
        else -> emptyList()
    }
    val cur = dev.valueOf(c)?.num?.roundToInt()
    val listState = rememberScalingLazyListState()

    Box(
        Modifier.fillMaxSize()
            // 全不透明。留一点半透明本想模仿 HyperOS 的玻璃层，但手表上没有实时模糊，
            // 底下的滑块和 chip 会直接糊在选项上，只是脏，不是层次。
            .background(Hyper.Bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    shortLabel(c.label),
                    color = Hyper.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            items(options.size) { i ->
                val (value, text) = options[i]
                Pill(
                    text = text,
                    accent = acc,
                    filled = cur == value,
                    onClick = { onPick(value) },
                    modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                )
            }
        }
    }
}

// ---------- 通用件 ----------

/** 胶囊按钮。选中态填身份色渐变，未选中是深灰——和卡片、chip 用同一套语言。 */
@Composable
private fun Pill(
    text: String,
    accent: Accent,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(42.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(21.dp))
            .background(Hyper.SurfaceHi)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (filled) Box(Modifier.fillMaxSize().background(accent.horizontal))
        Text(
            text,
            color = if (filled) Hyper.OnAccent else Hyper.OnSurface,
            fontSize = 14.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** 错误浮在底部，点掉即可，不占用布局位置——否则一次报错会把三张卡片挤下去。 */
@Composable
private fun BoxScope.ErrorToast(state: UiState, model: AppModel) {
    val err = state.error ?: return
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 30.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Hyper.Danger.copy(alpha = 0.16f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { model.dismissError() },
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            err, color = Hyper.Danger, fontSize = 11.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

private fun kelvinPresets(c: Control.Range): List<Pair<Int, String>> =
    listOf(2700, 3500, 5000, 6500).filter { it >= c.min && it <= c.max }.map { it to "${it}K" }

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
