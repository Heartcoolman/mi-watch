package dev.liji.mihome

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.TimeTextDefaults
import androidx.wear.compose.material3.timeTextCurvedText
import dev.liji.mihome.core.Control
import dev.liji.mihome.core.SceneInfo
import dev.liji.mihome.core.render
import dev.liji.mihome.core.shortLabel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun App(model: AppModel) {
    val s by model.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Hyper.Bg)) {
            AnimatedContent(
                targetState = s.screen,
                modifier = Modifier.fillMaxSize(),
                // 按类型 key：Login 每次二维码刷新都是新实例，按实例 key 会让整屏重新转场
                contentKey = { it::class },
                transitionSpec = {
                    when {
                        // 列表→详情：从 0.9 弹开——HyperOS 控制中心的展开手感
                        targetState is Screen.Detail -> fadeIn(tween(160)) +
                            scaleIn(initialScale = 0.9f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)) togetherWith
                            fadeOut(tween(120))

                        initialState is Screen.Detail -> fadeIn(tween(160)) togetherWith
                            fadeOut(tween(140)) + scaleOut(targetScale = 0.92f, animationSpec = tween(160))

                        else -> fadeIn(tween(220)) togetherWith fadeOut(tween(120))
                    }
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    is Screen.Loading -> LoadingScreen()
                    is Screen.Login -> LoginScreen(screen, model)
                    is Screen.Devices -> DeviceScreen(s, model)
                    is Screen.Detail -> {
                        val dev = s.devices.firstOrNull { it.did == screen.did }
                        if (dev == null) Centered { Text(stringResource(R.string.device_missing), color = Hyper.Muted) }
                        else DetailScreen(dev, s, model)
                    }
                }
            }
            ErrorToast(s, model)
        }
    }
}

@Composable
private fun LoadingScreen() {
    Centered {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Canvas(Modifier.size(24.dp)) { powerGlyph(accentOf(null).light) }
            CircularProgressIndicator(Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** 顶部弧形时间。压成 Muted 色——纯黑背景上默认的白色太抢。 */
@Composable
private fun HyperTimeText() {
    val style = TimeTextDefaults.timeTextStyle(color = Hyper.Muted)
    TimeText { time -> timeTextCurvedText(time, style) }
}

/**
 * 滚动屏的三件套：顶部弧形时间（滚动时淡出）、右缘滚动指示、圆屏收口。
 * TimeText 由 AppScaffold 承载，单独的 ScreenScaffold 不画它——实测过。
 * 所以每个滚动屏各包一层 AppScaffold，而不是放在 App 根部：
 * 详情页顶弧被设备名占用、登录页是二维码，都不该有时间，包在这里天然豁免。
 * scaffold 给的内边距不收：磁贴几何按整屏调准，autoCentering 已处理首行位置。
 */
@Composable
private fun ScrollScreen(listState: ScalingLazyListState, content: @Composable () -> Unit) {
    AppScaffold(timeText = { HyperTimeText() }) {
        ScreenScaffold(scrollState = listState) { _ -> content() }
    }
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
            Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.qr_desc), modifier = Modifier.size(170.dp))
        }
    } else {
        Centered {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Canvas(Modifier.size(20.dp)) { powerGlyph(Hyper.Muted) }
                Text(
                    screen.hint,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    color = Hyper.OnSurface,
                )
                Pill(
                    text = stringResource(R.string.qr_regen),
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
    ScrollScreen(listState) { DeviceList(state, model, listState) }
}

@Composable
private fun DeviceList(state: UiState, model: AppModel, listState: ScalingLazyListState) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(listState),
    ) {
        // 刷新失败时明说「这是旧状态」。不标的话，Doze 掐网后满屏正常颜色的开关全是假的
        if (state.stale) {
            item(key = "stale") {
                Text(
                    stringResource(R.string.stale_banner),
                    color = Hyper.Muted, fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }

        if (state.scenes.isNotEmpty()) {
            item(key = "scenes") { SceneRow(state.scenes, model) }
        }

        tileRows(state.favorites, model)

        if (state.devices.isEmpty()) {
            item(key = "empty") {
                Text(
                    // 空列表最常见的成因是账号在海外区域而请求发去了国服。
                    // 自动探测已经跑过一轮，还是空就得让用户知道往哪儿看。
                    state.progress ?: stringResource(if (state.busy) R.string.loading else R.string.no_devices),
                    color = Hyper.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp),
                )
            }
        } else if (state.progress != null) {
            item(key = "progress") { Text(state.progress, color = Hyper.Muted, fontSize = 11.sp) }
        }

        state.byRoom.forEach { (room, devs) ->
            item(key = "room:$room") { SectionHeader(room.ifEmpty { stringResource(R.string.ungrouped) }, devs.size) }
            tileRows(devs, model)
        }

        item(key = "actions") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Pill(
                    text = stringResource(if (state.busy) R.string.refreshing else R.string.refresh),
                    accent = accentOf(null), filled = false, onClick = { model.refresh() },
                )
                Pill(text = stringResource(R.string.sign_out), accent = accentOf(null), filled = false, onClick = { model.signOut() })
            }
        }
    }
}

/**
 * 场景 chip 行，排在收藏磁贴之上——「离家」这一下换来的是一串设备各就各位，
 * 比任何单设备都值得抢第一排。横向滚动，常用（米家 common_use）在前。
 *
 * 点击只给触觉 + 短暂高亮，不报「执行成功」：云端 code=0 只说明请求被接受，
 * 设备到底动没动没有可回读的凭据，报得比实际确定就是撒谎。
 */
@Composable
private fun SceneRow(scenes: List<SceneInfo>, model: AppModel) {
    val haptics = LocalHapticFeedback.current
    var fired by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(fired) {
        if (fired != null) {
            delay(900)
            fired = null
        }
    }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        scenes.forEach { s ->
            ScenePill(
                name = s.name,
                fired = fired == s.id,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    fired = s.id
                    model.runScene(s)
                },
            )
        }
    }
}

@Composable
private fun ScenePill(name: String, fired: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val acc = accentOf(null)
    val fill by animateFloatAsState(if (fired) 1f else 0f, label = "sceneFill")
    Box(
        modifier = Modifier
            .height(34.dp)
            .widthIn(max = 132.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(17.dp))
            .background(Hyper.SurfaceHi)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().alpha(fill).background(acc.horizontal))
        Text(
            "▸ $name",
            color = if (fired) Hyper.OnAccent else Hyper.OnSurface,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 11.dp),
        )
    }
}

/** 两两成行。落单的补一个等宽空位，免得最后一个被居中排版拽到中间。 */
private fun ScalingLazyListScope.tileRows(devs: List<Dev>, model: AppModel) {
    devs.chunked(2).forEach { pair ->
        // 行首 did 当 key：刷新后设备增减/换序时，没动的行不重建，滚动位置也稳
        item(key = pair.first().did) {
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
    val summary = d.summaryText

    val fill by animateFloatAsState(
        targetValue = if (on) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileFill",
    )
    // 文字色直接从 fill 推：一个磁贴一个动画对象就够，22 个磁贴少掉 22 个 animator
    val fg = lerp(Hyper.OnSurface, Hyper.OnAccent, fill)

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
@Composable
private fun offlineNote(d: Dev): String? =
    if (d.power != null && d.on == null) stringResource(R.string.offline) else null

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
    val heros = heroRanges(dev)
    // 设备一换索引就归零，否则上一台的第 2 个量会错位到这一台
    var heroIdx by remember(dev.did) { mutableStateOf(0) }
    val hero = heros.getOrNull(heroIdx.coerceAtMost(heros.lastIndex))
    var picking by remember { mutableStateOf<Control?>(null) }
    val faved = dev.did in state.favIds

    BackHandler { if (picking != null) picking = null else model.back() }

    // 既没有可拖的量也没有开关时（温湿度计、路由器、天然气报警器…），
    // 竖滑块就是个写着「关」的死灰块。这类设备改用列表式详情。
    if (hero == null && dev.power == null) {
        SensorDetail(dev, acc, faved, model) { picking = it }
        PickerLayer(dev, picking, acc, model) { picking = null }
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

        // 多个可拖量（空调：温度 + 风速）才显示切换器；单量设备一像素不变
        if (heros.size > 1) {
            HeroSwitcher(
                heros, heroIdx.coerceAtMost(heros.lastIndex), acc,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 31.dp),
            ) { heroIdx = it }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroSlider(
                dev = dev,
                range = hero,
                accent = acc,
                // 选择层开着时表圈事件不该落到滑块上
                rotaryActive = picking == null,
                onToggle = { model.toggle(dev.did) },
                onCommit = { v -> hero?.let { model.write(dev.did, it, DevValue(true, num = v)) } },
            )
            Spacer(Modifier.width(Dim.ColGap))
            SideColumn(dev, acc, heros, faved, model) { picking = it }
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

    PickerLayer(dev, picking, acc, model) { picking = null }
}

/**
 * 选择层的出入场。AnimatedVisibility 退场期间 picking 已是 null，
 * 所以要记住最后一次的控件，让淡出的那帧还有内容可画。
 */
@Composable
private fun PickerLayer(dev: Dev, picking: Control?, acc: Accent, model: AppModel, onClose: () -> Unit) {
    var last by remember { mutableStateOf<Control?>(null) }
    if (picking != null) last = picking
    AnimatedVisibility(
        visible = picking != null,
        enter = fadeIn(tween(160)) + slideInVertically(
            initialOffsetY = { it / 7 },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        ),
        exit = fadeOut(tween(120)) + slideOutVertically(targetOffsetY = { it / 9 }, animationSpec = tween(140)),
    ) {
        val c = picking ?: last ?: return@AnimatedVisibility
        PickerOverlay(dev, c, acc, onPick = { v ->
            when (c) {
                // 带入参的动作：选出的档就是那个唯一入参
                is Control.Act -> model.invokeArg(dev.did, c, v)
                is Control.Prop -> model.write(dev.did, c, DevValue(true, num = v.toDouble()))
            }
            onClose()
        }, onDismiss = onClose)
    }
}

/**
 * 可拖的主控件们＝主服务里的连续量。色温不算：跨度 2700–6500，滑起来没有可用精度。
 * 多于一个时（空调：温度 + 风速）标题下出现切换器。
 */
private fun heroRanges(d: Dev): List<Control.Range> =
    d.quick.filterIsInstance<Control.Range>().filter { !it.isKelvin && it.primary }

/** hero 切换器：一行小标签，点谁滑块就变成谁。 */
@Composable
private fun HeroSwitcher(
    heros: List<Control.Range>,
    current: Int,
    acc: Accent,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        heros.forEachIndexed { i, r ->
            val active = i == current
            Text(
                shortLabel(r.label),
                color = if (active) acc.light else Hyper.Muted,
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) Hyper.SurfaceHi else Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(i) }
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}

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
    rotaryActive: Boolean = true,
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

    // 表圈调值。旋转不像拖动有「松手」，所以停转 400ms 后才提交一次——
    // 每个事件都发 prop/set 会把蓝牙链路打满。rotaryGen 每转一格加一，
    // LaunchedEffect 随之重启，天然就是防抖；触摸落下把它清零，等于取消未提交的旋转。
    val focus = remember { FocusRequester() }
    var rotaryGen by remember { mutableStateOf(0) }
    LaunchedEffect(rotaryActive, canDrag) {
        if (rotaryActive && canDrag) focus.requestFocus()
    }
    LaunchedEffect(rotaryGen) {
        if (rotaryGen == 0) return@LaunchedEffect
        delay(400)
        dragging?.let {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onCommit(it)
        }
        dragging = null
    }

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
            .onRotaryScrollEvent { ev ->
                val r = range
                if (!canDrag || r == null || !rotaryActive) return@onRotaryScrollEvent false
                val span = r.max - r.min
                // 顺时针＝正值＝加；一整屏高的滚动量走完全量程，和触摸拖动同一比例
                val next = r.stepped((dragging ?: committed ?: r.min) + (ev.verticalScrollPixels / 480f) * span)
                if (next != dragging) {
                    dragging = next
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
                rotaryGen++
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(range, canDrag, canToggle, committed) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!canDrag && !canToggle) return@awaitEachGesture
                    rotaryGen = 0
                    val start = dragging ?: committed ?: range?.min ?: 0.0
                    val slop = viewConfiguration.touchSlop
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
                text = if (range != null && shown != null) heroText(shown, range) else stringResource(if (on) R.string.on else R.string.off),
                color = Color.White,
                fontSize = if (range != null) 25.sp else 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            // 裸数字第一次看不知道是什么量，压一行属性名；仍在顶部暗渐变的覆盖范围内
            if (range != null) {
                Text(
                    shortLabel(range.label),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
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
    heros: List<Control.Range>,
    faved: Boolean,
    model: AppModel,
    onPick: (Control) -> Unit,
) {
    val scroll = rememberScrollState()
    // 主开关已经并进滑块的点击；全部 hero 都归切换器管，右列不再重复
    val rest = dev.quick.filter { it !in heros && !(it is Control.Toggle && it.isPower) }

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

                // presets 为空（小数步进的量程枚举不出来）就别开弹层——一块白板不如只读显示
                is Control.Range -> Chip(
                    label = shortLabel(c.label),
                    value = dev.valueOf(c)?.num?.let { heroText(it, c) } ?: "—",
                    accent = acc, active = false,
                    onClick = if (c.presets().isEmpty()) null else ({ onPick(c) }),
                )

                // 摆风这类开关直接点切，没必要再弹一层
                is Control.Toggle -> {
                    val v = dev.valueOf(c)?.bool == true
                    Chip(
                        label = shortLabel(c.label), value = stringResource(if (v) R.string.on else R.string.off),
                        accent = acc, active = v,
                        onClick = { model.write(dev.did, c, DevValue(true, bool = !v)) },
                    )
                }

                // 动作：无入参点一下就发；带入参先弹层选档（空调设温、扫地机吸力）
                is Control.Act -> Chip(
                    label = shortLabel(c.label), value = if (c.arg != null) "…" else "▸",
                    accent = acc, active = false,
                    onClick = { if (c.arg != null) onPick(c) else model.invoke(dev.did, c) },
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
        label = stringResource(R.string.favorite),
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
private fun SensorDetail(dev: Dev, acc: Accent, faved: Boolean, model: AppModel, onPick: (Control) -> Unit) {
    val listState = rememberScalingLazyListState()
    ScrollScreen(listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(listState),
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
                item { Text(stringResource(R.string.no_readable), color = Hyper.Muted, fontSize = 12.sp) }
            }

            // 少数设备只读量之外还挂着零星可写项（天然气报警器的「远程消音」），一并摆出来
            val writables = dev.quick.filter { it !is Control.Readout }
            items(writables.size) { i ->
                when (val c = writables[i]) {
                    is Control.Toggle -> {
                        val v = dev.valueOf(c)?.bool == true
                        Pill(
                            text = "${shortLabel(c.label)} ${stringResource(if (v) R.string.on else R.string.off)}",
                            accent = acc, filled = v,
                            onClick = { model.write(dev.did, c, DevValue(true, bool = !v)) },
                            modifier = Modifier.padding(horizontal = 26.dp).fillMaxWidth(),
                        )
                    }
                    is Control.Act -> Pill(
                        text = "▸ ${shortLabel(c.label)}",
                        accent = acc, filled = false,
                        onClick = { if (c.arg != null) onPick(c) else model.invoke(dev.did, c) },
                        modifier = Modifier.padding(horizontal = 26.dp).fillMaxWidth(),
                    )

                    is Control.Prop -> if (c !is Control.Range || c.presets().isNotEmpty()) {
                        Pill(
                            text = shortLabel(c.label),
                            accent = acc, filled = false,
                            onClick = { onPick(c) },
                            modifier = Modifier.padding(horizontal = 26.dp).fillMaxWidth(),
                        )
                    } else Unit
                }
            }
            item {
                Pill(
                    text = stringResource(if (faved) R.string.faved_pill else R.string.fav_pill),
                    accent = acc, filled = faved,
                    onClick = { model.toggleFavorite(dev.did) },
                    modifier = Modifier.padding(horizontal = 30.dp).fillMaxWidth(),
                )
            }
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
fun readoutText(c: Control.Readout, v: DevValue): String =
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
    val fg = lerp(Hyper.OnSurface, Hyper.OnAccent, fill)

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
        // presets() 在 :core：色温 4 档，普通整数范围枚举/采样。
        // 旧版这里只给色温档，非色温 Range 点开是一整层白板。
        is Control.Range -> c.presets()
        is Control.Act -> c.arg?.options.orEmpty()
        else -> emptyList()
    }
    // 动作没有当前值可高亮——它是「做一次」，不是「处于某档」
    val cur = (c as? Control.Prop)?.let { dev.valueOf(it)?.num?.roundToInt() }
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
        ScrollScreen(listState) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                rotaryScrollableBehavior = RotaryScrollableDefaults.behavior(listState),
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
    val err = state.error
    // 退场动画期间 error 已被清掉，记住最后一条给淡出的那几帧用
    var last by remember { mutableStateOf("") }
    if (err != null) last = err
    AnimatedVisibility(
        visible = err != null,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(tween(160)) + slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        ),
        exit = fadeOut(tween(140)) + slideOutVertically(targetOffsetY = { it }, animationSpec = tween(160)),
    ) {
        Box(
            Modifier
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
                err ?: last, color = Hyper.Danger, fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
        }
    }
}



