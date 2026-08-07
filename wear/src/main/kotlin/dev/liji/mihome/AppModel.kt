package dev.liji.mihome

import android.content.Context
import android.graphics.Bitmap
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
import dev.liji.mihome.core.PropRef
import dev.liji.mihome.core.PropValue
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
}

/** 属性当前值。刻意不透出 JSON 类型，:wear 完全不依赖 kotlinx-serialization。 */
data class DevValue(val ok: Boolean, val bool: Boolean? = null, val num: Double? = null) {
    companion object {
        // asBool/asDouble 在逐项 code 非 0 时返回 null，离线设备不会渲染出陈旧值
        fun of(p: PropValue) = DevValue(p.code == 0, p.asBool, p.asDouble)
    }
}

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
    val power: Control.Toggle?
        get() = controls.filterIsInstance<Control.Toggle>().firstOrNull { it.isPower }

    val on: Boolean? get() = power?.let { values[it.siid to it.piid]?.bool }

    /** 表上默认只显示这些；其余是配置项，留给手机端。 */
    val quick: List<Control> get() = controls.filter { it.quick }

    /** 只读设备（温湿度计、人体存在、燃气…）：没有任何可写的常用控件。 */
    val readOnly: Boolean get() = quick.none { it !is Control.Readout }

    val readouts: List<Control.Readout> get() = quick.filterIsInstance<Control.Readout>()

    /**
     * 列表页要读的属性：电源 + 前两个只读量。
     * 只读量是传感器卡片的全部意义（「25.9° 45%」），电源是可控设备的全部意义，
     * 两者合起来一次 prop/get 就够渲染整个列表。
     */
    val listProps: List<Control.Prop> get() = listOfNotNull(power) + readouts.take(2)

    fun valueOf(c: Control.Prop): DevValue? = values[c.siid to c.piid]

    /** 磁贴上那行读数。渲染和存快照共用同一份，避免两处算得不一样。 */
    val summaryText: String? get() = summaryOf(this) ?: snapSummary
}

data class UiState(
    val screen: Screen = Screen.Loading,
    val devices: List<Dev> = emptyList(),
    /** 收藏的 did，有序。第一屏就是它们，零滚动。 */
    val favIds: List<String> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
    /** 首次启动拉 spec 时的进度文案；平时为 null。 */
    val progress: String? = null,
) {
    val favorites: List<Dev> get() = favIds.mapNotNull { id -> devices.firstOrNull { it.did == id } }

    /** 非收藏设备按房间分组，房间顺序按设备数降序——设备多的房间更可能是你要找的。 */
    val byRoom: List<Pair<String, List<Dev>>>
        get() = devices.filter { it.did !in favIds }
            .groupBy { it.room ?: "未分组" }
            .toList()
            .sortedByDescending { it.second.size }
}

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
                UiState(screen = Screen.Devices, devices = cached, favIds = loadFavorites())
            } else {
                UiState(favIds = loadFavorites())
            }
        },
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loginJob: Job? = null

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

    // ---------- 登录 ----------

    fun beginQrLogin() {
        loginJob?.cancel()
        _state.value = UiState(screen = Screen.Login(null, "正在生成二维码…"), favIds = loadFavorites())
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
                            _state.value = _state.value.copy(screen = Screen.Login(bmp, "用米家 App 扫码"))
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
                val hint = if (e is MiQrExpiredException) "二维码已过期，点这里重来" else "登录失败：${e.message}"
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

    fun back() {
        _state.value = _state.value.copy(screen = Screen.Devices, error = null)
    }

    // ---------- 设备 ----------

    /**
     * 分两段发布：设备清单一到就画，读数随后补。
     * 冷启动的四段请求里 prop/get 独占约 1.2 秒，没必要让名字和图标陪着一起等。
     */
    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    val devs = carryOver(buildDevices())
                    withContext(Dispatchers.Main) { _state.value = _state.value.copy(devices = devs) }
                    readProps(devs) { it.listProps }.also { finishLoad(it) }
                }
            }
                .onSuccess { _state.value = _state.value.copy(devices = it, busy = false) }
                .onFailure {
                    Flog.e("刷新失败", it)
                    _state.value = _state.value.copy(busy = false, error = friendly(it))
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
    }

    private fun loadDetail(did: String) {
        scope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching {
                withContext(Dispatchers.IO) {
                    val dev = _state.value.devices.firstOrNull { it.did == did } ?: error("设备不存在")
                    readProps(listOf(dev)) { d -> d.quick.filterIsInstance<Control.Prop>() }.first()
                }
            }.onSuccess { d ->
                putDev(d)
                _state.value = _state.value.copy(busy = false)
            }.onFailure {
                Flog.e("读取 $did 详情失败", it)
                _state.value = _state.value.copy(busy = false, error = friendly(it))
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
    fun invoke(did: String, c: Control.Act) {
        val dev = _state.value.devices.firstOrNull { it.did == did } ?: return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ok = Flog.timed("action $did ${c.siid}.${c.aiid}") { api.invokeAction(did, c.siid, c.aiid) }
                    check(ok) { "动作被拒绝" }
                }
            }.onFailure { e ->
                Flog.e("动作失败 $did ${c.siid}.${c.aiid}", e)
                _state.value = _state.value.copy(error = "${dev.name}：${friendly(e)}")
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
                    check(ok) { "写入被拒绝" }
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
                }
            }.onFailure { e ->
                Flog.e("写入失败 $did ${c.siid}.${c.piid}", e)
                putValue(did, c, prev, busy = false)
                _state.value = _state.value.copy(error = "${dev.name}：${friendly(e)}")
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
        _state.value = UiState(favIds = loadFavorites())
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
                            progress = "读取设备型号 ${done.incrementAndGet()}/${types.size}",
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
        e.message?.contains("Unable to resolve host") == true -> "网络不可用（表可能在休眠）"
        else -> e.message ?: e::class.simpleName ?: "未知错误"
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
