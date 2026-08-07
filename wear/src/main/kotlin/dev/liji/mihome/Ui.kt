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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.liji.mihome.core.Control
import dev.liji.mihome.core.render
import dev.liji.mihome.core.shortLabel
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
                    else DetailScreen(dev, s, model)
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
 * 收藏置顶，往下是按房间分组的全部设备。
 *
 * 布局是 2 列磁贴而不是整宽长条：长条为了放一个名字吃掉整行，一屏只有 3 个，
 * 22 个设备要滚 7 屏。两列之后一屏 5–6 个，而且传感器的读数能和名字并排放进同一格。
 *
 * 用 ScalingLazyColumn 承载：它会把靠近上下边缘的行自动缩小，正好补上圆屏的收口——
 * 这是自己算内边距做不到的。
 */
@Composable
private fun DeviceScreen(state: UiState, model: AppModel) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        tileRows(state.favorites, model)

        if (state.devices.isEmpty()) {
            item {
                Text(
                    // 空列表最常见的成因是账号在海外区域而请求发去了国服。
                    // 自动探测已经跑过一轮，还是空就得让用户知道往哪儿看。
                    state.progress ?: if (state.busy) "载入中…" else "没有设备\n可能是账号区域不对",
                    color = Hyper.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp),
                )
            }
        } else if (state.progress != null) {
            item { Text(state.progress, color = Hyper.Muted, fontSize = 11.sp) }
        }

        state.byRoom.forEach { (room, devs) ->
            item { SectionHeader(room, devs.size) }
            tileRows(devs, model)
        }

        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Pill(
                    text = if (state.busy) "刷新中" else "刷新",
                    accent = accentOf(null), filled = false, onClick = { model.refresh() },
                )
                Pill(text = "退出", accent = accentOf(null), filled = false, onClick = { model.signOut() })
            }
        }
    }
}

/** 两两成行。落单的补一个等宽空位，免得最后一个被居中排版拽到中间。 */
private fun ScalingLazyListScope.tileRows(devs: List<Dev>, model: AppModel) {
    devs.chunked(2).forEach { pair ->
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.TileGap)) {
                pair.forEach { d -> DeviceTile(d, model) }
                if (pair.size == 1) Spacer(Modifier.width(Dim.TileW))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        "$title  $count",
        color = Hyper.Muted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/**
 * 一格设备。可开关的点一下就切、长按进详情；只读的点哪儿都是进详情。
 *
 * 状态仍然由颜色表达（开＝身份色渐变铺满），但 80dp 的格子比长条多出一块空间，
 * 正好把传感器的读数直接印在名字下面——这是两列换来的最大好处，
 * 「卧室 25.9°」不用点进去看。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceTile(d: Dev, model: AppModel) {
    val acc = accentOf(d.category)
    val on = d.on == true
    val hasPower = d.power != null
    val live = hasPower && d.on != null
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val summary = summaryOf(d)

    val fill by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileFill",
    )
    val fg by animateColorAsState(
        targetValue = if (on) Hyper.OnAccent else Hyper.OnSurface,
        label = "tileFg",
    )

    Box(
        modifier = Modifier
            .size(Dim.TileW, Dim.TileH)
            .pressScale(interaction)
            .clip(RoundedCornerShape(Dim.TileRadius))
            .background(Hyper.Surface)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = !d.busy,
                // 能开关的整格就是开关；没有开关的（传感器、音箱）点了进详情
                onClick = { if (live) model.toggle(d.did) else model.open(d.did) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    model.open(d.did)
                },
            ),
    ) {
        Box(Modifier.fillMaxSize().alpha(fill).background(acc.horizontal))

        Column(Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceIconOrGlyph(d, if (on) Hyper.OnAccent else acc.deep, 24.dp, 18.dp)
                Spacer(Modifier.weight(1f))
                if (d.busy) {
                    Box(
                        Modifier.size(5.dp).clip(CircleShape)
                            .background(if (on) Hyper.OnAccent else Hyper.Muted),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                d.name,
                color = fg,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                // 两行：80dp 宽一行只放得下五六个汉字，而「温湿度计2（床）」这种
                // 靠后半截才能和另外四个温湿度计区分开
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp,
            )
            // 传感器的读数直接摆出来；异常状态优先于读数
            (offlineNote(d) ?: summary)?.let {
                Text(
                    it,
                    color = if (on) Hyper.OnAccent else acc.light,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 磁贴上的读数摘要，最多两项。
 *
 * spec 顺序不等于重要性，所以按类别排一遍：温湿度、电功率这些是「这台设备现在怎么样」，
 * 而 `status` 是个各家含义不同的通用字段——摄像机的 status 会显示成「不存在」
 * （存储卡状态），对着一台正常工作的摄像机报这个只会让人以为坏了。
 * 有开关的设备状态已经由颜色表达，就不再让 status 占这一行；
 * 没开关的传感器则相反，燃气报警器的 status（「监测正常」）正是它存在的理由。
 */
private val SUMMARY_RANK = listOf(
    "temperature", "relative-humidity", "electric-power", "occupancy-status",
    "gas-concentration", "smoke-concentration", "illumination",
    "download-speed", "upload-speed", "battery-level",
)

private fun summaryOf(d: Dev): String? = d.readouts
    .filterNot { d.power != null && it.cat == "status" }
    .sortedBy { SUMMARY_RANK.indexOf(it.cat).takeIf { i -> i >= 0 } ?: 50 }
    .take(2)
    .mapNotNull { c -> d.valueOf(c)?.let { readoutText(c, it) } }
    .filter { it != "—" }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" ")

/**
 * 米家原生图标优先，抓不到该型号时退回自绘的类别图形。
 *
 * 产品图底下要垫一层淡圆：米家的图标是**产品实拍**，深色产品（比如黑色开关面板）
 * 贴在深色卡片上几乎看不见。米家 App 里它们是衬在浅色卡片上的，我们这边是深色主题，
 * 所以得自己把这层衬底补回来。自绘图形不需要——它的颜色本来就是按主题选的。
 */
@Composable
private fun DeviceIconOrGlyph(d: Dev, glyphColor: Color, iconSize: Dp, glyphSize: Dp) {
    val icon = rememberDeviceIcon(d.model)
    if (icon != null) {
        Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(alpha = 0.13f)),
            )
            Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    } else {
        Canvas(Modifier.size(glyphSize)) { deviceGlyph(d.category, glyphColor) }
    }
}

/**
 * 只在真的异常时出字。
 * 「不支持开关」曾经是这里的一条：整宽长条时代只有可控设备上榜，没开关算异常。
 * 现在传感器是一等公民，22 个设备里一半没有开关，再报这句就是满屏噪音，
 * 而且把真正该显示的读数挤掉了。
 */
private fun offlineNote(d: Dev): String? = if (d.power != null && d.on == null) "离线" else null

// ---------- 设备详情 ----------

/**
 * 控制中心的展开面板：左边一根竖滑块承载最常调的那个量（灯＝亮度，空调＝温度），
 * 右边一列 chip 放模式/风速这些「选一个」的属性。
 *
 * 这样排的理由是使用频次：拖一下就能把亮度从 30 调到 80，而旧版的 −/+ 步进要点 50 次；
 * 模式一天换不了一次，收进 chip 后面完全不亏。
 */
@Composable
private fun DetailScreen(dev: Dev, state: UiState, model: AppModel) {
    val acc = accentOf(dev.category)
    val hero = heroRange(dev)
    var picking by remember { mutableStateOf<Control.Prop?>(null) }
    val faved = dev.did in state.favIds

    BackHandler { if (picking != null) picking = null else model.back() }

    // 既没有可拖的量也没有开关时（温湿度计、路由器、天然气报警器…），
    // 竖滑块就是个写着「关」的死灰块。这类设备改用列表式详情。
    if (hero == null && dev.power == null) {
        SensorDetail(dev, acc, faved, model) { picking = it }
        picking?.let { c ->
            PickerOverlay(dev, c, acc, onPick = { v ->
                model.write(dev.did, c, DevValue(true, num = v.toDouble()))
                picking = null
            }, onDismiss = { picking = null })
        }
        return
    }

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
            SideColumn(dev, acc, hero, faved, model) { picking = it }
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
    // 音箱、投影仪这类设备根本没有电源属性。原来的判断把它们一律当成「关」，
    // 结果滑块变灰**而且拖不动**——音量完全没法调。没有开关就没有关闭态，
    // 这类设备的滑块应该始终是亮的、可拖的。
    val hasPower = dev.power != null
    val on = !hasPower || dev.on == true
    val canToggle = hasPower && dev.on != null
    val canDrag = range != null && (!hasPower || dev.on != null)
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
            .pointerInput(range, canDrag, canToggle, committed) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!canDrag && !canToggle) return@awaitEachGesture
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
                        if (moved && canDrag && range != null) {
                            ch.consume()
                            // 满高＝满量程；向上拖是加，所以取负
                            val span = range.max - range.min
                            cur = range.stepped(start + (-dy / size.height) * span)
                            dragging = cur
                        }
                    }
                    if (moved && canDrag && range != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onCommit(cur)
                        dragging = null
                    } else if (!moved && canToggle) {
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
            // 电源符号放在滑块底部，兼作「点这块能开关」的提示——纯手势没有可发现性。
            // 没有电源属性的设备不画：那会暗示一个点了没反应的开关。
            if (canToggle) {
                Canvas(Modifier.size(17.dp)) {
                    powerGlyph(if (on) Hyper.OnAccent else Hyper.Muted)
                }
            }
        }
    }
}

private fun heroText(v: Double, c: Control.Range): String = c.render(v)

/**
 * 右列：模式/风速这类「选一个」的属性各占一个 chip，点开才弹选择层。
 * 一屏放得下三个，第四个之后要滚——但排在前面的都是常调的。
 */
@Composable
private fun SideColumn(
    dev: Dev,
    acc: Accent,
    hero: Control.Range?,
    faved: Boolean,
    model: AppModel,
    onPick: (Control.Prop) -> Unit,
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

                // 无入参动作：点一下就发，没有状态可显示
                is Control.Act -> Chip(
                    label = shortLabel(c.label), value = "▸",
                    accent = acc, active = false,
                    onClick = { model.invoke(dev.did, c) },
                )

                is Control.Readout -> Unit // 见 readoutLine()
            }
        }
        FavChip(faved, acc) { model.toggleFavorite(dev.did) }
    }
}

/** 收藏开关。排在右列最后——加/取消收藏一年也做不了几次。 */
@Composable
private fun FavChip(faved: Boolean, acc: Accent, onClick: () -> Unit) {
    Chip(
        label = "收藏",
        value = if (faved) "★" else "☆",
        accent = acc,
        active = faved,
        onClick = onClick,
    )
}

/**
 * 只读设备的详情：温湿度计、人体存在、路由器、门锁、体脂秤。
 * 没有可写属性，所以整页就是一列读数——把 spec 里所有能读的都摆出来，
 * 比列表页那两个摘要值多。
 */
@Composable
private fun SensorDetail(dev: Dev, acc: Accent, faved: Boolean, model: AppModel, onPick: (Control.Prop) -> Unit) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Text(dev.name, color = Hyper.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val labels = dev.readouts.map { shortLabel(it.label) }
        items(dev.readouts.size) { i ->
            val c = dev.readouts[i]
            // 门锁有两个「电池电量」（门锁本体 + 猫眼），去掉服务前缀后会撞名，撞了就用全名
            val label = if (labels.count { it == labels[i] } > 1) c.label else labels[i]
            ReadoutRow(label, dev.valueOf(c)?.let { readoutText(c, it) } ?: "—", acc)
        }
        if (dev.readouts.isEmpty()) {
            item { Text("这个设备没有可读的属性", color = Hyper.Muted, fontSize = 12.sp) }
        }

        // 少数设备只读量之外还挂着零星可写项（天然气报警器的「远程消音」），一并摆出来
        val writables = dev.quick.filter { it !is Control.Readout }
        items(writables.size) { i ->
            when (val c = writables[i]) {
                is Control.Toggle -> {
                    val v = dev.valueOf(c)?.bool == true
                    Pill(
                        text = "${shortLabel(c.label)} ${if (v) "开" else "关"}",
                        accent = acc, filled = v,
                        onClick = { model.write(dev.did, c, DevValue(true, bool = !v)) },
                        modifier = Modifier.padding(horizontal = 26.dp).fillMaxWidth(),
                    )
                }
                is Control.Act -> Pill(
                    text = "▸ ${shortLabel(c.label)}",
                    accent = acc, filled = false,
                    onClick = { model.invoke(dev.did, c) },
                    modifier = Modifier.padding(horizontal = 26.dp).fillMaxWidth(),
                )

                is Control.Prop -> Pill(
                    text = shortLabel(c.label),
                    accent = acc, filled = false,
                    onClick = { onPick(c) },
                    modifier = Modifier.padding(horizontal = 26.dp).fillMaxWidth(),
                )
            }
        }
        item {
            Pill(
                text = if (faved) "★ 已收藏" else "☆ 收藏",
                accent = acc, filled = faved,
                onClick = { model.toggleFavorite(dev.did) },
                modifier = Modifier.padding(horizontal = 30.dp).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReadoutRow(label: String, value: String, acc: Accent) {
    Row(
        Modifier.padding(horizontal = 26.dp).fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(Hyper.Surface)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, color = Hyper.Muted, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(value, color = acc.light, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 底部状态行：把所有只读量压成一行「标签 值」。 */
private fun readoutLine(dev: Dev): String? = dev.readouts
    .mapNotNull { c ->
        val t = dev.valueOf(c)?.let { readoutText(c, it) } ?: return@mapNotNull null
        if (t == "—") null else shortLabel(c.label) + " " + t
    }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" · ")

/** 渲染实现在 :core，CLI 的 `./mi list` 和表上走同一份代码。 */
private fun readoutText(c: Control.Readout, v: DevValue): String =
    if (!v.ok) "—" else c.render(v.num, v.bool)

private fun shortLabel(s: String) = s.shortLabel()

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
    c: Control.Prop,
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


