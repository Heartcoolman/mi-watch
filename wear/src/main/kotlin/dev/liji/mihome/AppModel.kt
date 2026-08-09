package dev.liji.mihome

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.liji.mihome.core.Control
import dev.liji.mihome.core.MiApi
import dev.liji.mihome.core.HomeInfo
import dev.liji.mihome.core.MiAuth
import dev.liji.mihome.core.MiQrExpiredException
import dev.liji.mihome.core.MiSessionExpiredException
import dev.liji.mihome.core.PropRef
import dev.liji.mihome.core.PropValue
import dev.liji.mihome.core.SceneInfo
import dev.liji.mihome.core.Session
import dev.liji.mihome.core.SpecInstance
import dev.liji.mihome.core.clearSession
import dev.liji.mihome.core.loadSession
import dev.liji.mihome.core.saveSession
import dev.liji.mihome.core.toControls
import dev.liji.mihome.core.urnCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

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

fun summaryOf(d: Dev): String? = d.readouts
    .filterNot { d.power != null && it.cat == "status" }
    .sortedBy { SUMMARY_RANK.indexOf(it.cat).takeIf { i -> i >= 0 } ?: 50 }
    .take(2)
    .mapNotNull { c -> d.valueOf(c)?.let { readoutText(c, it) } }
    .filter { it != "—" }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" ")


typealias PropKey = Pair<Int, Int>

sealed interface Screen {
    data object Loading : Screen
    data class Login(val qr: Bitmap?, val hint: String) : Screen
    data object Devices : Screen
    data class Detail(val did: String) : Screen
    /** 收藏排序。顺序同时决定首屏和 Tile 六格的排列。 */
    data object Favorites : Screen
}

/** 属性当前值。刻意不透出 JSON 类型，:wear 完全不依赖 kotlinx-serialization。 */
@Immutable
data class DevValue(val ok: Boolean, val bool: Boolean? = null, val num: Double? = null) {
    companion object {
        // asBool/asDouble 在逐项 code 非 0 时返回 null，离线设备不会渲染出陈旧值
        fun of(p: PropValue) = DevValue(p.code == 0, p.asBool, p.asDouble)
    }
}

@Immutable
data class Dev(
    val did: String,
    val name: String,
    val online: Boolean,
    /** spec urn 的类别段（light / air-conditioner …），UI 靠它取身份色和自绘图形。 */
    val category: String? = null,
    /** 产品型号，米家原生图标按它在 assets/icon 里找。 */
    val model: String? = null,
    /** 房间名。归属来自 gethome 的 roomlist（设备记录里没有这个字段）。 */
    val room: String? = null,
    /** 米家记的使用次数，只用于首次启动时挑收藏。 */
    val cnt: Int = 0,
    /** 快照里带回来的读数文案。真实读数没到之前先顶上，免得磁贴空着。 */
    val snapSummary: String? = null,
    val controls: List<Control> = emptyList(),
    val values: Map<PropKey, DevValue> = emptyMap(),
    val busy: Boolean = false,
) {
    // 这些派生值原来是 get()：写起来干净，代价是每次重组都要 filter/sort 一遍，
    // 22 个磁贴乘起来就是滑动掉帧的底噪。Dev 不可变，改成每实例算一次的 lazy，
    // 语义不变，计算从「每帧 N 次」变成「每次数据变化 1 次」。
    val power: Control.Toggle? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        controls.filterIsInstance<Control.Toggle>().firstOrNull { it.isPower }
    }

    val on: Boolean? by lazy(LazyThreadSafetyMode.PUBLICATION) { power?.let { values[it.siid to it.piid]?.bool } }

    /** 表上默认只显示这些；其余是配置项，留给手机端。 */
    val quick: List<Control> by lazy(LazyThreadSafetyMode.PUBLICATION) { controls.filter { it.quick } }

    /** 只读设备（温湿度计、人体存在、燃气…）：没有任何可写的常用控件。 */
    val readOnly: Boolean get() = quick.none { it !is Control.Readout }

    val readouts: List<Control.Readout> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        quick.filterIsInstance<Control.Readout>()
    }

    /**
     * 列表页要读的属性：电源 + 前两个只读量。
     * 只读量是传感器卡片的全部意义（「25.9° 45%」），电源是可控设备的全部意义，
     * 两者合起来一次 prop/get 就够渲染整个列表。
     */
    val listProps: List<Control.Prop> get() = listOfNotNull(power) + readouts.take(2)

    fun valueOf(c: Control.Prop): DevValue? = values[c.siid to c.piid]

    /** 磁贴上那行读数。渲染和存快照共用同一份，避免两处算得不一样。 */
    val summaryText: String? by lazy(LazyThreadSafetyMode.PUBLICATION) { summaryOf(this) ?: snapSummary }
}

@Immutable
data class UiState(
    val screen: Screen = Screen.Loading,
    val devices: List<Dev> = emptyList(),
    /** 手动场景，常用（米家的 common_use 标记）在前。自动化不在其中。 */
    val scenes: List<SceneInfo> = emptyList(),
    /** 收藏的 did，有序。第一屏就是它们，零滚动。 */
    val favIds: List<String> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    /** 首次启动拉 spec 时的进度文案；平时为 null。 */
    val progress: String? = null,
    /**
     * 上次刷新失败，当前显示的是缓存/快照里的旧状态。
     * 不标出来的话，Doze 掐网后满屏「看起来正常」的开关全是假的。
     */
    val stale: Boolean = false,
) {
    // get() 版本的 byRoom 在合成期做 groupBy + 排序，且**每次读都全量重算**——
    // 这是列表页每帧成本里最大的一块纯浪费。状态不可变，lazy 一次就够。
    val favorites: List<Dev> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        favIds.mapNotNull { id -> devices.firstOrNull { it.did == id } }
    }

    /** 非收藏设备按房间分组，房间顺序按设备数降序——设备多的房间更可能是你要找的。 */
    val byRoom: List<Pair<String, List<Dev>>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        devices.filter { it.did !in favIds }
            .groupBy { it.room.orEmpty() }
            .toList()
            .sortedByDescending { it.second.size }
    }
}

@Stable
class AppModel(private val app: Context) {

    /** 首屏收藏的容量。三张 52dp 卡片正好在 226dp 上居中排完，零滚动。 */
    private val autoFavoriteCount = 3

    /**
     * 首次启动挑收藏时优先考虑的品类：每天顺手就按的东西。
     *
     * 只按米家的使用次数排会把摄像机排进前三（它 cnt=11，比桌灯的 8 高）——
     * 摄像机能开关，但那不是抬腕会做的动作。这个偏好只影响**初始默认值**，
     * 用户在详情页 ★ 一改就以他的选择为准。
     */
    private val everydayCategories = listOf(
        "light", "switch", "outlet", "air-conditioner", "fan", "curtain", "heater", "humidifier",
    )

    /** 家庭表（含房间）变化极少，一个进程生命周期内取一次就够——省掉每次刷新的一个蓝牙往返。 */
    private var homesCache: List<HomeInfo>? = null

    /** 区域探测一次启动最多跑一轮，免得每次刷新都对着七个区域各发一次请求。 */
    private var regionProbed = false

    private val store = AndroidStore(app)
    private val api = MiApi(store, MiAuth(store))
    private val specs by lazy { SpecCache.client(app) }

    /**
     * spec 标签的语言。之前写死 zh_cn——对非中文用户，属性名会变成一半中文一半英文
     * （翻译里有的取中文，没有的落回 spec 自带的英文 description）。
     * miot-spec 的 multiLanguage 只提供 en 和 zh_cn 两种。
     */
    private val specLang = if (java.util.Locale.getDefault().language == "zh") "zh_cn" else "en"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 初始状态就带上收藏和上次的设备快照。
     *
     * 收藏不能等到 start()：调试入口（--es open/toggle）会绕过 start()，
     * 那时 favIds 是空的，点一下 ★ 就把整份收藏替换成了一个设备。
     *
     * 快照也要在这里，而不是 start() 里：MainActivity 是先 setContent 再 start()，
     * 快照晚一步就意味着 Compose 先合成一遍 Loading 转圈、再整体重组成设备网格——
     * 白做一次完整合成，而首次合成正是冷启动里最贵的一段。
     */
    private val _state = MutableStateFlow(
        Snapshot.load(app).let { cached ->
            if (cached.isNotEmpty() && store.loadSession() != null) {
                UiState(
                    screen = Screen.Devices,
                    devices = cached,
                    // 场景快照直接借 Tile 的缓存：两边要的都是 id+name，没必要再存一份
                    scenes = SceneTileState.load(app).map { SceneInfo(it.id, it.name, 0L) },
                    favIds = loadFavorites(),
                )
            } else {
                UiState(favIds = loadFavorites())
            }
        },
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loginJob: Job? = null

    /**
     * refresh / loadDetail 各持一个 Job，新请求启动前取消旧的。
     * 不取消的话，快速进出详情页时旧请求的迟到响应会覆盖新状态——
     * 和「调完音量自己跳回去」是同源问题，只是发生在导航而不是回读上。
     */
    private var refreshJob: Job? = null
    private var detailJob: Job? = null

    /** 上次刷新成功的时刻（elapsedRealtime，不受墙钟调整影响）。回前台时据此决定要不要重刷。 */
    private var lastRefreshAt = 0L

    /**
     * 错误的统一出口。会话彻底失效（passToken 也换不动了）唯一的出路是重新扫码，
     * 直接送回登录页——甩一条「无 ssecurity」让用户反复点刷新是最差的结局。
     * 协程取消不是错误，静默吞掉。
     */
    private fun reportError(e: Throwable, prefix: String = "") {
        when (e) {
            is kotlinx.coroutines.CancellationException -> Unit
            is MiSessionExpiredException -> {
                // 并发的几笔请求会一起撞上过期，每笔都 signOut 会反复吹掉刚生成的二维码
                if (_state.value.screen !is Screen.Login) {
                    Flog.w("会话已失效，回登录页")
                    signOut()
                }
            }
            else -> _state.value = _state.value.copy(error = prefix + friendly(e))
        }
    }

    fun start() {
        if (store.loadSession() != null) {
            // 快照已在初始状态里画好了（见 _state），这里只管去刷新
            Flog.i("快照 ${_state.value.devices.size} 个设备")
            _state.value = _state.value.copy(screen = Screen.Devices)
            refresh()
        } else {
            beginQrLogin()
        }
    }

    /**
     * 回到前台。Activity 没被系统回收时抬腕回来只走 onResume，不经 onCreate，
     * 所以在这之前屏上一直是离开时的状态——期间手机 App、自动化、别的家庭成员
     * 都可能改过设备，而 stale 标记只在**刷新失败**时才亮，于是一屏看着正常的
     * 颜色和读数全是过期的。这正是 README 里说要避免的「过期状态冒充实时状态」。
     *
     * 节流 30 秒：来回切表盘和 App 是常见动作，每次都发一轮请求会白耗蓝牙链路和电。
     */
    fun onResume() {
        if (store.loadSession() == null) return
        if (_state.value.screen is Screen.Login) return
        if (refreshJob?.isActive == true) return
        if (SystemClock.elapsedRealtime() - lastRefreshAt < RESUME_REFRESH_INTERVAL_MS) return
        Flog.i("回前台，重新刷新")
        refresh()
    }

    // ---------- 登录 ----------

    fun beginQrLogin() {
        loginJob?.cancel()
        _state.value = UiState(screen = Screen.Login(null, app.getString(R.string.qr_generating)), favIds = loadFavorites())
        loginJob = scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // 客户端必须在绑定块内部构造，否则会复用原网络的连接，绑定白做。
                    Net.withWifi(app) { bound ->
                        Flog.i("开始扫码登录 (wifi=$bound)")
                        val auth = MiAuth(store)
                        val ch = Flog.timed("qr-gen") { auth.startQrLogin() }
                        val bmp = qrBitmap(ch.qrData)
                        withContext(Dispatchers.Main) {
                            _state.value = _state.value.copy(screen = Screen.Login(bmp, app.getString(R.string.qr_scan_hint)))
                        }
                        Flog.timed("qr-poll") { auth.awaitQrScan(ch.lp) }
                    }
                }
            }.onSuccess {
                Flog.i("登录成功 userId=${it.userId}")
                _state.value = UiState(screen = Screen.Devices, favIds = loadFavorites())
                refresh()
            }.onFailure { e ->
                Flog.e("登录失败", e)
                val hint = if (e is MiQrExpiredException) app.getString(R.string.qr_expired) else app.getString(R.string.login_failed, e.message.orEmpty())
                _state.value = UiState(screen = Screen.Login(null, hint), favIds = loadFavorites())
            }
        }
    }

    /**
     * 开发期兜底：桌面 harness 取到的会话直接注入。
     * `adb shell am start -n dev.liji.mihome/.MainActivity --es session '<blob>'`
     */
    fun importSession(blob: String): Boolean = runCatching {
        val f = String(Base64.getUrlDecoder().decode(blob)).split("\n")
        require(f.size == 6) { "字段数不对: ${f.size}" }
        store.saveSession(Session(f[0], f[1], f[2], f[3], f[4], f[5]))
        Flog.i("会话已注入 userId=${f[0]}")
        _state.value = UiState(screen = Screen.Devices, favIds = loadFavorites())
        refresh()
        true
    }.onFailure { Flog.e("会话注入失败", it) }.getOrDefault(false)

    // ---------- 导航 ----------

    fun open(did: String) {
        _state.value = _state.value.copy(screen = Screen.Detail(did), error = null)
        loadDetail(did)
    }

    fun openFavorites() {
        _state.value = _state.value.copy(screen = Screen.Favorites, error = null)
    }

    fun back() {
        // 离开详情页就取消它的加载：迟到的响应不该覆盖列表页刚刷出来的状态。
        // busy 要在这里收掉——被取消的 loadDetail 不会再把它清掉，
        // 不清的话「刷新」会永远显示成「刷新中」。刷新真在跑时以它为准。
        detailJob?.cancel()
        _state.value = _state.value.copy(
            screen = Screen.Devices,
            error = null,
            busy = refreshJob?.isActive == true,
        )
    }

    // ---------- 设备 ----------

    /**
     * 分两段发布：设备清单一到就画，读数随后补。
     * 冷启动的四段请求里 prop/get 独占约 1.2 秒，没必要让名字和图标陪着一起等。
     */
    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    // homes() 先跑一次占住缓存，否则下面两个并发分支会各发一次 gethome
                    val hs = homes()
                    // 场景和设备清单互不依赖，并行拉。场景失败不拖垮刷新——它只是少一行 chip，
                    // 用 null 区分「拉失败」和「真没有」：失败保留旧场景，别让整行闪没。
                    val scenesJob = async {
                        runCatching { Flog.timed("scenes") { hs.flatMap { api.scenesOf(it.id) } } }
                            .onFailure { Flog.w("场景拉取失败: ${it.message}") }
                            .getOrNull()
                            ?.sortedByDescending { it.commonUse }
                    }
                    val devs = carryOver(buildDevices())
                    val scenes = scenesJob.await()
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            devices = devs,
                            scenes = scenes ?: _state.value.scenes,
                        )
                    }
                    // 阻塞的 OkHttp 调用不理会取消，返回后要自查：被新一轮刷新顶掉的旧请求
                    // 若继续执行 finishLoad，会拿更旧的数据覆盖 Tile 缓存和快照
                    ensureActive()
                    if (scenes != null) syncSceneTile(scenes)
                    readProps(devs) { it.listProps }.also {
                        ensureActive()
                        finishLoad(it)
                    }
                }
            }
                .onSuccess {
                    lastRefreshAt = SystemClock.elapsedRealtime()
                    _state.value = _state.value.copy(devices = keepBusyValues(it), busy = false, stale = false)
                }
                .onFailure {
                    // 被新一轮刷新取消的旧请求不该动任何状态
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    Flog.e("刷新失败", it)
                    // 刷新失败＝屏上是旧状态。标出来，别让快照冒充实时
                    _state.value = _state.value.copy(busy = false, stale = true)
                    reportError(it)
                }
        }
    }

    /** 加载设备后立刻切换指定设备。给 `deploy.sh test` 的无人值守验证用。 */
    fun startThenToggle(did: String) {
        _state.value = _state.value.copy(screen = Screen.Devices)
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { withContext(Dispatchers.IO) { loadDevices() } }
                .onSuccess {
                    _state.value = _state.value.copy(devices = it, busy = false)
                    Flog.i("盲测：设备已加载 ${it.size} 个，开始切换 $did")
                    toggle(did)
                }
                .onFailure {
                    Flog.e("盲测：加载设备失败", it)
                    _state.value = _state.value.copy(busy = false, error = friendly(it))
                }
        }
    }

    /** 加载设备后直接进某个设备的详情。给截图核对与 adb 调试用。 */
    fun startThenOpen(did: String) {
        scope.launch {
            _state.value = _state.value.copy(screen = Screen.Devices, busy = true, error = null)
            runCatching { withContext(Dispatchers.IO) { loadDevices() } }
                .onSuccess {
                    _state.value = _state.value.copy(devices = it, busy = false)
                    open(did)
                }
                .onFailure {
                    Flog.e("加载设备失败", it)
                    _state.value = _state.value.copy(busy = false, error = friendly(it))
                }
        }
    }

    private fun loadDevices(): List<Dev> =
        readProps(buildDevices()) { it.listProps }.also { finishLoad(it) }

    /** 设备清单（不含读数）。这一段跑完就能画出磁贴，不必等 prop/get。 */
    private fun buildDevices(): List<Dev> {
        val homes = homes()
        val rooms = homes.flatMap { it.rooms.entries }.associate { it.key to it.value }

        // 全部家庭都要读。一个账号有多套房产是常态（本机账号就有三个家庭），
        // 只读第一个会让另外几处的设备在 App 里完全不存在。
        // 并发发出去：三个家庭串行要 0.9 秒，而它们之间没有任何依赖。
        val infos = Flog.timed("device_list x${homes.size}") {
            if (homes.size <= 1) {
                homes.flatMap { api.devices(it.uid, it.id) }
            } else {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(homes.size.coerceAtMost(4))
                try {
                    homes.map { h -> pool.submit<List<dev.liji.mihome.core.DeviceInfo>> { api.devices(h.uid, h.id) } }
                        .flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
                } finally {
                    pool.shutdown()
                }
            }
        }.distinctBy { it.did }

        val controlsByType = prefetchControls(infos.mapNotNull { it.specType }.distinct())

        return infos
            .map { info ->
                Dev(
                    did = info.did,
                    name = info.name,
                    online = info.online,
                    category = info.specType?.urnCategory(),
                    model = info.model,
                    room = rooms[info.did],
                    cnt = info.cnt,
                    controls = controlsByType[info.specType].orEmpty(),
                )
            }
            // 有常用控件的排前面：连电量都读不到的设备（体脂秤）沉底
            .sortedWith(compareByDescending<Dev> { it.quick.isNotEmpty() }.thenBy { it.name })
    }

    private fun finishLoad(devs: List<Dev>) {
        seedFavorites(devs)
        syncTile(devs)
        Snapshot.save(app, devs)
        // 后台把图标全解出来，之后滚动列表不会再有解码停顿
        scope.launch { DeviceIcon.prewarm(app, devs.map { it.model }) }
    }

    /**
     * 刷新的回读可能比飞行中的写更旧：冷启动的 prop/get 在用户点开关**之前**就发出去了，
     * 它带回来的是旧值，照单全收会把刚打开的灯在界面上打回关——和 write() 里
     * 「调完音量自己跳回去」（75a0b28）同一类病，只是发生在刷新这条路上。
     * busy=true 说明这台设备正有一笔写在途，它的值以写操作自己的回读为准。
     */
    private fun keepBusyValues(fresh: List<Dev>): List<Dev> {
        val cur = _state.value.devices.associateBy { it.did }
        return fresh.map { d ->
            val c = cur[d.did]
            if (c?.busy == true) d.copy(values = c.values, busy = true) else d
        }
    }

    /** 新一批设备沿用界面上已有的值（多半来自快照），避免刚画出来的磁贴又空一次。 */
    private fun carryOver(fresh: List<Dev>): List<Dev> {
        val old = _state.value.devices.associateBy { it.did }
        return fresh.map { d ->
            old[d.did]?.let { d.copy(values = it.values, snapSummary = it.snapSummary) } ?: d
        }
    }

    /**
     * 把最后已知状态写进缓存，Tile 靠它瞬间出图（它不能发网络请求）。
     *
     * 收藏排在前面，然后用其他可开关设备补满六格——卡片一屏就这么大，
     * 空着的格子不会因为「你还没收藏够」而变得有用。顺序取自设备列表本身的排序，
     * 所以设备集合不变时位置是稳定的，肌肉记忆不会被打乱。
     * 只读传感器不进：卡片上的每一格都是一次点击，放个点不动的东西等于浪费一格。
     */
    private fun syncTile(devs: List<Dev>) {
        val fav = _state.value.favIds
        val switchable = devs.filter { it.power != null }
        val ordered = switchable.sortedBy { fav.indexOf(it.did).takeIf { i -> i >= 0 } ?: (fav.size + 1) }
        TileState.save(
            app,
            ordered.take(TILE_SLOTS).mapNotNull { d ->
                d.power?.let { TileState.Item(d.did, d.name, d.on, it.siid, it.piid, d.category) }
            },
        )
        MiTileService.requestUpdate(app)
        MiComplicationService.requestUpdate(app)
    }

    private fun loadDetail(did: String) {
        detailJob?.cancel()
        detailJob = scope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val dev = _state.value.devices.firstOrNull { it.did == did } ?: error(app.getString(R.string.device_missing))
                    readProps(listOf(dev)) { d -> d.quick.filterIsInstance<Control.Prop>() }.first()
                }
            }.onSuccess { d ->
                putDev(d)
                _state.value = _state.value.copy(busy = false)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) return@onFailure
                Flog.e("读取 $did 详情失败", it)
                _state.value = _state.value.copy(busy = false)
                reportError(it)
            }
        }
    }

    /** 一次 prop/get 读多个设备的多个属性——批量是蓝牙链路上最划算的优化。 */
    private fun readProps(devs: List<Dev>, pick: (Dev) -> List<Control.Prop>): List<Dev> {
        val refs = devs.flatMap { d -> pick(d).map { PropRef(d.did, it.siid, it.piid) } }
        if (refs.isEmpty()) return devs
        // 分批：设备多的家庭一次能凑出几百个属性，请求体过大会被服务端拒。
        // 批量仍然是蓝牙链路上最划算的优化，所以批子开得尽量大。
        val got = Flog.timed("prop/get x${refs.size}") {
            refs.chunked(PROP_BATCH).flatMap { api.propGet(it) }
        }
        val byKey = got.associateBy { Triple(it.did, it.siid, it.piid) }
        return devs.map { d ->
            val merged = d.values.toMutableMap()
            pick(d).forEach { c ->
                byKey[Triple(d.did, c.siid, c.piid)]?.let { merged[c.siid to c.piid] = DevValue.of(it) }
            }
            d.copy(values = merged)
        }
    }

    fun toggle(did: String) {
        val dev = _state.value.devices.firstOrNull { it.did == did } ?: return
        val power = dev.power ?: return
        write(did, power, DevValue(true, bool = !(dev.on ?: false)))
    }

    /**
     * 写一个属性。乐观更新：先本地翻转再发请求，失败回滚。
     * 蓝牙下一次写要 200–600ms，没有即时反馈会让人以为没按上。
     */
    /**
     * 触发一个无入参动作。动作是即发即忘：没有可回读的值，也就没有乐观更新和回滚。
     * 红外空调、投影仪遥控、扫地机启停走的都是这条路。
     */
    /**
     * 执行一个手动场景。和 invoke() 一样即发即忘：场景没有可回读的状态，
     * 也就没有乐观更新和回滚。UI 侧只给触觉和短暂高亮，不报「成功」——
     * 云端 code=0 只说明请求被接受，设备到底动没动没有凭据。
     */
    fun runScene(s: SceneInfo) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ok = Flog.timed("scene ${s.name}") { api.runScene(s.id) }
                    check(ok) { app.getString(R.string.rejected_scene) }
                }
            }.onFailure { e ->
                Flog.e("场景失败 ${s.id}", e)
                reportError(e, "${s.name}：")
            }
        }
    }

    /** 场景 Tile 的缓存。列表本身就是常用在前，Tile 取前几个正好。 */
    private fun syncSceneTile(scenes: List<SceneInfo>) {
        SceneTileState.save(app, scenes.map { SceneTileState.Item(it.id, it.name) })
        SceneTileService.requestUpdate(app)
    }

    fun invoke(did: String, c: Control.Act) {
        val dev = _state.value.devices.firstOrNull { it.did == did } ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ok = Flog.timed("action $did ${c.siid}.${c.aiid}") { api.invokeAction(did, c.siid, c.aiid) }
                    check(ok) { app.getString(R.string.rejected_action) }
                }
            }.onFailure { e ->
                Flog.e("动作失败 $did ${c.siid}.${c.aiid}", e)
                reportError(e, "${dev.name}：")
            }
        }
    }

    /** 带一个入参的动作：值来自选择层的档位。同样即发即忘，没有可回读的状态。 */
    fun invokeArg(did: String, c: Control.Act, value: Int) {
        val dev = _state.value.devices.firstOrNull { it.did == did } ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ok = Flog.timed("action $did ${c.siid}.${c.aiid}($value)") {
                        api.invokeAction(did, c.siid, c.aiid, value)
                    }
                    check(ok) { app.getString(R.string.rejected_action) }
                }
            }.onFailure { e ->
                Flog.e("动作失败 $did ${c.siid}.${c.aiid}($value)", e)
                reportError(e, "${dev.name}：")
            }
        }
    }

    fun write(did: String, c: Control.Prop, target: DevValue) {
        val dev = _state.value.devices.firstOrNull { it.did == did } ?: return
        val prev = dev.valueOf(c)
        putValue(did, c, target, busy = true)

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val label = "prop/set $did ${c.siid}.${c.piid}"
                    val ok = Flog.timed(label) {
                        when (c) {
                            is Control.Toggle -> api.setBool(did, c.siid, c.piid, target.bool == true)
                            is Control.Range -> api.setNumber(
                                did, c.siid, c.piid, c.stepped(target.num ?: 0.0), c.integral,
                            )
                            is Control.Choice -> api.setInt(did, c.siid, c.piid, (target.num ?: 0.0).toInt())
                            is Control.Readout -> error("只读属性不可写")
                        }
                    }
                    check(ok) { app.getString(R.string.rejected_write) }
                    // 回读一到两次。快设备一次就确认，慢的再给一次机会。
                    var seen: DevValue? = null
                    for (wait in listOf(700L, 1500L)) {
                        delay(wait)
                        seen = readProps(listOf(dev)) { listOf(c) }.first().valueOf(c)
                        if (matches(seen, target, c)) break
                    }
                    seen
                }
            }.onSuccess { actual ->
                val v = reconcile(actual, target, prev, c)
                putValue(did, c, v, busy = false)
                if (c == dev.power) {
                    TileState.put(app, did, v.bool)
                    MiTileService.requestUpdate(app)
                    MiComplicationService.requestUpdate(app)
                }
            }.onFailure { e ->
                Flog.e("写入失败 $did ${c.siid}.${c.piid}", e)
                putValue(did, c, prev, busy = false)
                reportError(e, "${dev.name}：")
            }
        }
    }

    private fun loadFavorites(): List<String> =
        store.get(KEY_FAV)?.split(",")?.filter { it.isNotBlank() }.orEmpty()

    /**
     * 首次启动时替用户挑几个收藏：取前几个能开关的设备。
     *
     * 不能硬编码 did——那是某一户人家的设备号，换个账号就全是空的。
     * 挑「能开关的」是因为收藏区的卡片点一下就是开关，只读传感器放进去没有意义。
     * 挑完就落盘，之后完全以用户在详情页 ★ 的选择为准。
     */
    private fun seedFavorites(devs: List<Dev>) {
        if (store.get(KEY_FAV) != null) return
        // 先按品类偏好，再按米家自己记的使用次数。第一版用的是设备列表的默认顺序
        // （名字字母序），结果给我挑出了「卧室灯 + 两台摄像机」。
        val seed = devs.filter { it.power != null }
            .sortedWith(
                compareBy<Dev> { everydayCategories.indexOf(it.category).takeIf { i -> i >= 0 } ?: 99 }
                    .thenByDescending { it.cnt },
            )
            .take(autoFavoriteCount)
            .map { it.did }
        store.set(KEY_FAV, seed.joinToString(","))
        _state.value = _state.value.copy(favIds = seed)
        Flog.i("首次启动，自动收藏 ${seed.size} 个可开关设备")
    }

    /** 调试用：清掉收藏，让自动挑选重新跑一次。 */
    fun resetFavorites() {
        store.set(KEY_FAV, null)
        _state.value = _state.value.copy(favIds = emptyList())
        Flog.i("收藏已清空，将重新自动挑选")
        refresh()
    }

    /** 加入/移出收藏。收藏只存在表上，不回写米家。 */
    fun toggleFavorite(did: String) {
        val cur = _state.value.favIds
        val next = if (did in cur) cur - did else cur + did
        store.set(KEY_FAV, next.joinToString(","))
        _state.value = _state.value.copy(favIds = next)
        syncTile(_state.value.devices)
    }

    /**
     * 在收藏里上移（delta=-1）或下移（delta=+1）一个设备。
     * 越界不是错误——排序页的箭头到顶/到底就该点不动，这里再兜一次。
     */
    fun moveFavorite(did: String, delta: Int) {
        val cur = _state.value.favIds.toMutableList()
        val i = cur.indexOf(did)
        if (i < 0) return
        // 只和**界面上看得见的**那个收藏对调。收藏是本地持久化的，设备在米家侧被删掉后
        // did 仍留在表里、但不在 devices 中，排序页也就不画它；直接按 ±1 取下标会和这个
        // 隐形条目交换，表现是点一下什么都没动。跳过它们，找下一个还在的。
        val known = _state.value.devices.mapTo(HashSet()) { it.did }
        val j = generateSequence(i + delta) { it + delta }
            .takeWhile { it in cur.indices }
            .firstOrNull { cur[it] in known } ?: return
        cur[i] = cur[j].also { cur[j] = cur[i] }
        store.set(KEY_FAV, cur.joinToString(","))
        _state.value = _state.value.copy(favIds = cur)
        // Tile 的六格按收藏顺序排，所以每动一次都要同步过去
        syncTile(_state.value.devices)
    }

    /**
     * 决定写入之后界面该显示什么。
     *
     * 回读的意义是「设备可能把值钳到别处」——空调设 33° 会被钳成 30°，不认这个就会显示错。
     * 但**不能盲信回读**：`prop/get` 返回的是云端缓存的「上次上报值」，不是设备现状。
     * 小米智能音箱 Pro 实测：写音量 code=0、喇叭立刻响应，而回读**整整 50 秒**才跟上。
     * 原来的实现拿这个陈旧值覆盖乐观值，表现就是「调到 40% 过一下自己跳回 60%」。
     *
     * 三分支判据：
     *  - 读到目标值 → 确认了，用它
     *  - 读到的还是改之前的值 → 设备八成还没上报，保留乐观值，别把界面打回去
     *  - 读到第三个值 → 设备真的改了主意（钳制），听它的
     */
    private fun reconcile(actual: DevValue?, target: DevValue, prev: DevValue?, c: Control.Prop): DevValue = when {
        actual == null || !actual.ok -> target
        matches(actual, target, c) -> actual
        prev != null && matches(actual, prev, c) -> target
        else -> actual
    }

    private fun matches(a: DevValue?, b: DevValue?, c: Control.Prop): Boolean {
        if (a == null || b == null) return false
        a.bool?.let { return it == b.bool }
        val x = a.num ?: return false
        val y = b.num ?: return false
        // 目标值发出去前会经过 stepped()，所以比较的是钳过步进之后的数
        val yy = if (c is Control.Range) c.stepped(y) else y
        return kotlin.math.abs(x - yy) < 0.5
    }

    fun signOut() {
        store.clearSession()
        // 退出的主场景是换账号：上一个账号的快照、Tile、场景、收藏全部作废。
        // 留着的话，新账号会在 Tile 上看见别人家的设备名，点下去还会对着无权的
        // did/scene_id 发请求；进程内的家庭缓存和区域探测标记同样要归零。
        Snapshot.save(app, emptyList())
        TileState.save(app, emptyList())
        SceneTileState.save(app, emptyList())
        store.set(KEY_FAV, null)
        homesCache = null
        regionProbed = false
        MiTileService.requestUpdate(app)
        SceneTileService.requestUpdate(app)
        MiComplicationService.requestUpdate(app)
        _state.value = UiState()
        beginQrLogin()
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ---------- 内部 ----------

    private fun putDev(d: Dev) {
        _state.value = _state.value.copy(
            devices = _state.value.devices.map { if (it.did == d.did) d else it },
        )
    }

    private fun putValue(did: String, c: Control.Prop, v: DevValue?, busy: Boolean) {
        _state.value = _state.value.copy(
            devices = _state.value.devices.map { d ->
                if (d.did != did) d
                else d.copy(
                    busy = busy,
                    values = d.values.toMutableMap().apply {
                        if (v == null) remove(c.siid to c.piid) else put(c.siid to c.piid, v)
                    },
                )
            },
        )
    }

    private companion object {
        const val KEY_FAV = "favorites"

        /** 一次 prop/get 的属性上限。实测 58 个没问题，留出余量。 */
        const val PROP_BATCH = 80

        /** Tile 的格数：2 列 × 3 行。 */
        const val TILE_SLOTS = 6

        /** 回前台后多久之内不重复刷新。 */
        const val RESUME_REFRESH_INTERVAL_MS = 30_000L
    }

    /**
     * 全部家庭（含房间表）。首次调用时顺带把账号归属的区域定下来。
     *
     * 区域必须自动探测：登录流程全球统一，但业务接口按区域分开（海外是
     * de./sg./us./ru./i2./tw. 前缀）。选错的表现是「登录成功但一个设备都没有」，
     * 让用户自己猜是开源项目里最难自查的一类问题。判据是「能返回非空家庭列表」——
     * 错误区域返回的是成功但空的结果，看状态码分不出来。
     */
    private fun homes(): List<HomeInfo> {
        homesCache?.let { return it }
        var list = Flog.timed("gethome") { api.homes() }
        if (list.isEmpty() && !regionProbed) {
            regionProbed = true
            val r = Flog.timed("区域探测") { api.detectRegion() }
            Flog.i("账号区域 = ${r ?: "未探测到"}")
            if (r != null) list = api.homes()
        }
        if (list.isNotEmpty()) homesCache = list
        return list
    }

    private fun controlsOf(spec: SpecInstance): List<Control> =
        spec.toControls(specs.translations(spec.type, specLang))

    /**
     * 并发预取 spec 并归约成控件。
     *
     * 打包进 assets 的 spec 只对「构建这个包的人自己家」有效。别人装上以后，一屋子设备的
     * spec 全要现拉，每个型号还要两次请求（spec + 中文翻译）。串行拉在蓝牙链路上是
     * 20–40 秒的白屏——这是开源版和自用版最大的体感差别。
     *
     * 并发度只给 3：蓝牙代理是条窄管子，开太多反而互相拖慢（这也是最初计划里
     * 「不要对着蓝牙代理并发十几个请求」那条的由来）。命中缓存的型号根本不发请求，
     * 所以这个代价只在首次启动、以及新增设备时付一次。
     */
    private fun prefetchControls(types: List<String>): Map<String, List<Control>> {
        val missing = types.count { !specs.isCached(it) }
        if (missing > 0) Flog.i("需要拉取 $missing 个型号的 spec")
        val done = java.util.concurrent.atomic.AtomicInteger()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        val out = java.util.concurrent.ConcurrentHashMap<String, List<Control>>()
        try {
            types.map { t ->
                pool.submit {
                    runCatching { out[t] = controlsOf(specs.spec(t)) }
                        .onFailure { Flog.w("spec 取用失败 $t: ${it.message}") }
                    if (missing > 0) {
                        _state.value = _state.value.copy(
                            progress = app.getString(R.string.progress_specs, done.incrementAndGet(), types.size),
                        )
                    }
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
            _state.value = _state.value.copy(progress = null)
        }
        return out
    }

    /** UnknownHostException 在这台表上最常见的成因是深度 Doze 掐了网络，直接说人话。 */
    private fun friendly(e: Throwable): String = when {
        e.message?.contains("Unable to resolve host") == true -> app.getString(R.string.net_unavailable)
        else -> e.message ?: e::class.simpleName ?: app.getString(R.string.unknown_error)
    }

    private fun qrBitmap(data: String, size: Int = 360): Bitmap {
        val m = QRCodeWriter().encode(
            data, BarcodeFormat.QR_CODE, size, size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 2, // 静默区压到 2 个模块：33mm 圆屏每个像素都要省
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until m.width) for (y in 0 until m.height) {
            bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
        }
        return bmp
    }
}
