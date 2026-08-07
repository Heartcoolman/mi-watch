package dev.liji.mihome

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * HyperOS 设计语言在 226dp 圆屏上的落地。
 *
 * 手机端 HyperOS 的核心是「液态玻璃」——实时模糊 + 折射 + 分层。手表上这三样都不做：
 * 圆屏没有壁纸可透，OLED 上纯黑才省电，而实时模糊在 Wear 的 GPU 预算里是奢侈品。
 * 真正能搬过来、也是 HyperOS 观感来源的是另外四条：
 *   1. 大圆角 / 胶囊形——卡片圆角取到半高，接近超椭圆的柔和感
 *   2. 控制中心式的「亮块」——开启态用饱和渐变填满整块，关闭态退回深灰
 *   3. 自然色而非炫彩——灯是暖阳色，空调是冷蓝，取自设备本身给人的直觉
 *   4. 按下即回弹的弹簧反馈——HyperOS「活起来」的观感一半来自动效
 *
 * 字体本想用 MiSans（HyperOS 的官方字体），但中文字重全集约 30MB，
 * 装进一个手表 App 不合适，改用系统字重区分层级。
 */
object Hyper {
    /** OLED 上纯黑不点亮像素，既省电又让圆屏边界消失。 */
    val Bg = Color(0xFF000000)

    /** 关闭态卡片。比纯黑高一档，圆角边界才看得见。 */
    val Surface = Color(0xFF1B1D22)

    /** 滑块槽、次级 chip。 */
    val SurfaceHi = Color(0xFF272A31)

    val OnSurface = Color(0xFFF1F2F4)

    /** 亮块上的文字：深色比白色更清晰——饱和渐变的亮端配白字对比度不够。 */
    val OnAccent = Color(0xFF14161A)

    val Muted = Color(0xFF868C96)

    val Danger = Color(0xFFFF6B6B)

    /** 关闭态滑块的填充：保留档位可见，但不发光。 */
    val DimFill = Brush.verticalGradient(listOf(Color(0xFF4A4E57), Color(0xFF33363D)))
}

/**
 * 设备的身份色。取自设备类别而不是配色表下标——同类设备颜色一致，
 * 加一个新灯不用去改任何映射。
 */
data class Accent(val light: Color, val deep: Color) {
    /** 竖向滑块：亮端在上，像光从底部升起来。 */
    val vertical get() = Brush.verticalGradient(listOf(light, deep))

    /** 横向卡片：亮端在左，跟随阅读方向。 */
    val horizontal get() = Brush.horizontalGradient(listOf(light, deep))
}

fun accentOf(category: String?): Accent = when (category) {
    "light", "light-strip", "night-light" -> Accent(Color(0xFFFFD08A), Color(0xFFFF8A34)) // 暖阳
    "air-conditioner", "air-condition" -> Accent(Color(0xFF8ADBFF), Color(0xFF2A86FF))    // 冷蓝
    "fan", "air-fresh", "ventilation-fan", "air-purifier" -> Accent(Color(0xFF9BEFD6), Color(0xFF17B98A))
    "temperature-humidity-sensor", "thermometer", "air-monitor" -> Accent(Color(0xFF9FE8DA), Color(0xFF2FA894))
    "humidifier", "water-purifier", "kettle", "washer", "dishwasher" -> Accent(Color(0xFFA8E4FF), Color(0xFF3AA6D9))
    "lock", "door", "magnet-sensor" -> Accent(Color(0xFFFFDDA0), Color(0xFFD08A2E))
    "camera", "doorbell" -> Accent(Color(0xFFD8B6FF), Color(0xFF8A4FE0))
    "speaker", "television", "projector", "tv-box", "stb", "monitor" -> Accent(Color(0xFFFFB8D9), Color(0xFFE0508F))
    "router", "repeater", "gateway" -> Accent(Color(0xFFB6C4FF), Color(0xFF4B63FF))
    "gas-sensor", "smoke-sensor", "water-sensor" -> Accent(Color(0xFFFFC0A8), Color(0xFFE0603A))
    "occupancy-sensor", "motion-sensor" -> Accent(Color(0xFFC8E8A0), Color(0xFF6FA83A))
    "curtain", "window-opener", "airer" -> Accent(Color(0xFFFFD9A8), Color(0xFFB98149))
    "vacuum", "robot" -> Accent(Color(0xFFA8E8C8), Color(0xFF35A87A))
    else -> Accent(Color(0xFFB6C4FF), Color(0xFF5B72FF))
}

// ---------- 图标 ----------

/**
 * 图标用 Canvas 画而不是引依赖。
 * material-icons 会为了两个图形拖进整个图标库，而本机只有阿里云镜像，
 * 每加一个依赖就多一个解析失败面。三十行代码换掉一个依赖是划算的。
 */
fun DrawScope.deviceGlyph(category: String?, color: Color) {
    val s = size.minDimension
    val w = s * 0.11f
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, Offset(s * x1, s * y1), Offset(s * x2, s * y2), w, StrokeCap.Round)
    fun ring(r: Float, cx: Float = 0.5f, cy: Float = 0.5f) =
        drawCircle(color, s * r, Offset(s * cx, s * cy), style = Stroke(w))
    fun dot(r: Float, cx: Float = 0.5f, cy: Float = 0.5f) =
        drawCircle(color, s * r, Offset(s * cx, s * cy))
    /** 同心弧，用来表达「发射/感应」：信号、声音、气体、雷达都是这个意思。 */
    fun waves(count: Int, cx: Float, cy: Float, from: Float = 0.20f) {
        repeat(count) { i ->
            val r = s * (from + i * 0.16f)
            drawArc(
                color, startAngle = 205f, sweepAngle = 130f, useCenter = false,
                topLeft = Offset(s * cx - r, s * cy - r),
                size = Size(r * 2, r * 2),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
    }

    when (category) {
        "light", "light-strip", "night-light" -> {
            val r = s * 0.185f
            dot(0.185f)
            repeat(8) { i ->
                val a = i * 45.0 * Math.PI / 180.0
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                drawLine(
                    color,
                    center + Offset(dx * r * 1.7f, dy * r * 1.7f),
                    center + Offset(dx * r * 2.45f, dy * r * 2.45f),
                    w, StrokeCap.Round,
                )
            }
        }

        // 三条长短不一的气流线，比雪花好画也更好认
        "air-conditioner", "air-condition", "fan", "air-fresh", "ventilation-fan",
        "hood", "air-purifier", "heater", "clothes-dryer",
        -> listOf(0.30f to 0.80f, 0.50f to 1.00f, 0.70f to 0.62f).forEach { (y, len) ->
            line(0.5f - 0.40f * len, y, 0.5f + 0.40f * len, y)
        }

        // 温度计：一根柱子 + 底部一个球
        "temperature-humidity-sensor", "thermometer" -> {
            line(0.5f, 0.20f, 0.5f, 0.60f)
            dot(0.155f, 0.5f, 0.72f)
        }

        // 水滴
        "humidifier", "water-purifier", "kettle", "dishwasher", "washer" -> {
            line(0.5f, 0.20f, 0.30f, 0.58f)
            line(0.5f, 0.20f, 0.70f, 0.58f)
            drawArc(
                color, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(s * 0.28f, s * 0.36f), size = Size(s * 0.44f, s * 0.44f),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }

        // 感应/报警类：一个源点 + 同心弧
        "occupancy-sensor", "motion-sensor", "gas-sensor", "smoke-sensor",
        "magnet-sensor", "water-sensor", "air-monitor",
        -> { dot(0.10f, 0.30f, 0.5f); waves(3, 0.30f, 0.5f) }

        // 信号塔：底座 + 同心弧
        "router", "repeater", "gateway" -> { line(0.28f, 0.78f, 0.72f, 0.78f); dot(0.09f, 0.5f, 0.78f); waves(3, 0.5f, 0.78f, 0.22f) }

        // 挂锁
        "lock", "door" -> {
            drawRoundRect(
                color, topLeft = Offset(s * 0.24f, s * 0.46f), size = Size(s * 0.52f, s * 0.36f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.09f), style = Stroke(w),
            )
            drawArc(
                color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(s * 0.34f, s * 0.22f), size = Size(s * 0.32f, s * 0.32f),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }

        // 镜头
        "camera", "doorbell" -> { ring(0.30f); dot(0.11f) }

        // 音箱：箱体 + 一个发声圈
        "speaker", "tv-box", "intercom" -> {
            drawRoundRect(
                color, topLeft = Offset(s * 0.28f, s * 0.16f), size = Size(s * 0.44f, s * 0.68f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f), style = Stroke(w),
            )
            ring(0.12f, 0.5f, 0.58f)
        }

        // 屏幕
        "television", "projector", "monitor", "stb" -> {
            drawRoundRect(
                color, topLeft = Offset(s * 0.14f, s * 0.24f), size = Size(s * 0.72f, s * 0.46f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.09f), style = Stroke(w),
            )
            line(0.34f, 0.84f, 0.66f, 0.84f)
        }

        // 窗帘：两片波浪
        "curtain", "window-opener", "airer" -> {
            line(0.14f, 0.18f, 0.86f, 0.18f)
            line(0.32f, 0.26f, 0.32f, 0.82f)
            line(0.68f, 0.26f, 0.68f, 0.82f)
        }

        // 插座/开关面板
        "switch", "outlet", "electric-power", "plug" -> {
            drawRoundRect(
                color, topLeft = Offset(s * 0.18f, s * 0.18f), size = Size(s * 0.64f, s * 0.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.16f), style = Stroke(w),
            )
            dot(0.07f, 0.39f, 0.44f); dot(0.07f, 0.61f, 0.44f)
        }

        // 扫地机：圆盘 + 一条边刷
        "vacuum", "robot" -> { ring(0.30f); dot(0.09f, 0.5f, 0.5f) }

        // 遥控器
        "remote-control" -> {
            drawRoundRect(
                color, topLeft = Offset(s * 0.32f, s * 0.14f), size = Size(s * 0.36f, s * 0.72f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f), style = Stroke(w),
            )
            dot(0.065f, 0.5f, 0.34f); dot(0.065f, 0.5f, 0.58f)
        }

        else -> { ring(0.28f); dot(0.075f) }
    }
}

/** 电源符号：缺口朝正上方的圆弧 + 一竖。 */
fun DrawScope.powerGlyph(color: Color) {
    val w = size.minDimension * 0.13f
    val r = size.minDimension / 2 - w
    // Compose 的 0° 在 3 点方向、顺时针为正，270° 是正上方；缺口要以它为中心
    drawArc(
        color = color,
        startAngle = 300f, sweepAngle = 300f, useCenter = false,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2, r * 2),
        style = Stroke(width = w, cap = StrokeCap.Round),
    )
    drawLine(
        color,
        Offset(center.x, center.y - r * 1.28f),
        Offset(center.x, center.y - r * 0.15f),
        w, StrokeCap.Round,
    )
}

// ---------- 动效 ----------

/**
 * 按下缩到 0.94 再弹回。手表上没有鼠标悬停，按压反馈是「这块能点」的唯一提示；
 * 用弹簧而不是线性补间，是 HyperOS 手感的主要来源。
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

// ---------- 圆屏尺寸 ----------

/**
 * 480px @340dpi ＝ 226dp 直径，半径 113dp。圆屏上任何一行的可用宽度是
 * 2·√(113² − y²)，y 是离圆心的竖直距离。下面这些常量都是照这个式子算出来的：
 *
 *   |y|=70  → 177dp   竖滑块（140dp 高）的上下端，决定了 82+6+82 的三段分配
 *   |y|=86  → 147dp   三张 52dp 卡片里最外侧那张的边缘，决定了 174dp 卡宽
 *
 * 写死成常量而不是按屏幕比例算：这个 App 只跑在这一块表上，
 * 按比例缩放反而会让好不容易调准的边距在别处失准。
 */
object Dim {
    /**
     * 2 列磁贴。整宽长条为了放一个名字占掉全部横向空间，22 个设备要滚 7 屏；
     * 两列之后一屏能看 5–6 个。80+8+80 = 168dp 宽，居中后离圆心最远处
     * 需要 |y| ≤ √(113²−84²) ≈ 75dp——正好是中间两行的范围，
     * 更外侧的行交给 ScalingLazyColumn 自己缩小，圆屏的收口是免费的。
     */
    val TileW = 80.dp
    val TileH = 82.dp
    val TileGap = 8.dp
    val TileRadius = 22.dp

    val CardH = 52.dp
    val CardGap = 8.dp
    val CardPad = 26.dp // 左右各留 26dp → 卡宽 174dp

    val HeroW = 82.dp
    val HeroH = 140.dp
    val ColGap = 6.dp
    val ChipW = 82.dp
    val ChipH = 42.dp
    val ChipGap = 5.dp

    val CardRadius = 26.dp
    val HeroRadius = 28.dp
    val ChipRadius = 21.dp
}
