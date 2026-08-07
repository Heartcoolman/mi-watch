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
    val controls: List<Control> = emptyList(),
    val values: Map<PropKey, DevValue> = emptyMap(),
    val busy: Boolean = false,
) {
    val power: Control.Toggle?
        get() = controls.filterIsInstance<Control.Toggle>().firstOrNull { it.isPower }

    val on: Boolean? get() = power?.let { values[it.siid to it.piid]?.bool }

    /** 表上默认只显示这些；其余是配置项，留给手机端。 */
    val quick: List<Control> get() = controls.filter { it.quick }

    fun valueOf(c: Control): DevValue? = values[c.siid to c.piid]
}

data class UiState(
    val screen: Screen = Screen.Loading,
    val devices: List<Dev> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
)

class AppModel(private val app: Context) {

    /** v1 只做这三个：卧室灯、桌灯、空调。改这一行就能扩。 */
    private val pinned = listOf("889297205", "899794381", "495582022")

    private val store = AndroidStore(app)
    private val api = MiApi(store, MiAuth(store))
    private val specs by lazy { SpecCache.client(app) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loginJob: Job? = null

    fun start() {
        if (store.loadSession() != null) {
            _state.value = _state.value.copy(screen = Screen.Devices)
            refresh()
        } else {
            beginQrLogin()
        }
    }

    // ---------- 登录 ----------

    fun beginQrLogin() {
        loginJob?.cancel()
        _state.value = UiState(screen = Screen.Login(null, "正在生成二维码…"))
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
                _state.value = UiState(screen = Screen.Devices)
                refresh()
            }.onFailure { e ->
                Flog.e("登录失败", e)
                val hint = if (e is MiQrExpiredException) "二维码已过期，点这里重来" else "登录失败：${e.message}"
                _state.value = UiState(screen = Screen.Login(null, hint))
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
        _state.value = UiState(screen = Screen.Devices)
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

    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { withContext(Dispatchers.IO) { loadDevices() } }
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

    private fun loadDevices(): List<Dev> {
        val (uid, homeId) = homeIds()
        val devs = Flog.timed("device_list") { api.devices(uid, homeId) }
            .filter { it.did in pinned && !it.isBle }
            .map { info ->
                val controls = info.specType?.let { t ->
                    runCatching { controlsOf(specs.spec(t)) }
                        .onFailure { Flog.w("spec 取用失败 ${info.did}: ${it.message}") }
                        .getOrNull()
                }.orEmpty()
                Dev(did = info.did, name = info.name, online = info.online, controls = controls)
            }
            .sortedBy { pinned.indexOf(it.did) }

        // 列表页只读开关：一次批量请求拿全部，蓝牙链路上少一个往返就是几百毫秒
        return readProps(devs) { listOfNotNull(it.power) }.also { syncTile(it) }
    }

    /** 把最后已知状态写进缓存，Tile 靠它瞬间出图（它不能发网络请求）。 */
    private fun syncTile(devs: List<Dev>) {
        TileState.save(
            app,
            devs.mapNotNull { d ->
                d.power?.let { TileState.Item(d.did, d.name, d.on, it.siid, it.piid) }
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
                    readProps(listOf(dev)) { d -> d.quick }.first()
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
    private fun readProps(devs: List<Dev>, pick: (Dev) -> List<Control>): List<Dev> {
        val refs = devs.flatMap { d -> pick(d).map { PropRef(d.did, it.siid, it.piid) } }
        if (refs.isEmpty()) return devs
        val got = Flog.timed("prop/get x${refs.size}") { api.propGet(refs) }
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
    fun write(did: String, c: Control, target: DevValue) {
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
                    delay(800)
                    // 回读确认：设备可能拒绝或钳制到别的值（比如空调温度超范围）
                    readProps(listOf(dev)) { listOf(c) }.first().valueOf(c)
                }
            }.onSuccess { actual ->
                val v = actual ?: target
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

    fun signOut() {
        store.clearSession()
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

    private fun putValue(did: String, c: Control, v: DevValue?, busy: Boolean) {
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

    private fun homeIds(): Pair<Long, Long> {
        store.get("homeId")?.toLongOrNull()?.let { id ->
            store.get("homeOwnerUid")?.toLongOrNull()?.let { return it to id }
        }
        val home = Flog.timed("gethome") { api.homes() }.firstOrNull() ?: error("未取到家庭列表")
        store.set("homeId", home.id.toString())
        store.set("homeOwnerUid", home.uid.toString())
        Flog.i("默认家庭 ${home.name} id=${home.id}")
        return home.uid to home.id
    }

    private fun controlsOf(spec: SpecInstance): List<Control> =
        spec.toControls(specs.translations(spec.type))

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
