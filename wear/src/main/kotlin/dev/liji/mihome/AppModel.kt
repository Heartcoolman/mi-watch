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
import dev.liji.mihome.core.Session
import dev.liji.mihome.core.SpecInstance
import dev.liji.mihome.core.clearSession
import dev.liji.mihome.core.code
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

sealed interface Screen {
    data object Loading : Screen
    data class Login(val qr: Bitmap?, val hint: String) : Screen
    data object Devices : Screen
}

data class Dev(
    val did: String,
    val name: String,
    val online: Boolean,
    val power: Control.Toggle? = null,
    /** null = 未知或不可用（读回来的逐项 code 非 0），UI 显示「—」并禁用。 */
    val on: Boolean? = null,
    val busy: Boolean = false,
)

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
                    // 客户端必须在绑定块内部构造，否则会复用蓝牙代理的连接，绑定白做。
                    Net.withWifi(app) { bound ->
                        Flog.i("开始扫码登录 (wifi=$bound)")
                        val auth = MiAuth(store)
                        val ch = Flog.timed("qr-gen") { auth.startQrLogin() }
                        val bmp = qrBitmap(ch.qrData)
                        withContext(Dispatchers.Main) {
                            _state.value = _state.value.copy(
                                screen = Screen.Login(bmp, "用米家 App 扫码"),
                            )
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
     * 蓝牙代理万一走不完登录状态机，这条路能让开发继续——
     * passToken 长期有效且永不轮换，在 Mac 上成功一次就永久够用。
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

    // ---------- 设备 ----------

    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            runCatching { withContext(Dispatchers.IO) { loadDevices() } }
                .onSuccess { _state.value = _state.value.copy(devices = it, busy = false) }
                .onFailure {
                    Flog.e("刷新失败", it)
                    _state.value = _state.value.copy(busy = false, error = it.message ?: "刷新失败")
                }
        }
    }

    private fun loadDevices(): List<Dev> {
        val (uid, homeId) = homeIds()
        val devs = Flog.timed("device_list") { api.devices(uid, homeId) }
            .filter { it.did in pinned && !it.isBle }
            .map { info ->
                val power = info.specType?.let { t ->
                    runCatching { powerToggle(specs.spec(t)) }
                        .onFailure { Flog.w("spec 取用失败 ${info.did}: ${it.message}") }
                        .getOrNull()
                }
                Dev(did = info.did, name = info.name, online = info.online, power = power)
            }
            .sortedBy { pinned.indexOf(it.did) }

        return readPower(devs)
    }

    /** 一次批量 prop/get 拿全部开关状态——蓝牙代理上少一个往返就是少几百毫秒。 */
    private fun readPower(devs: List<Dev>): List<Dev> {
        val refs = devs.mapNotNull { d -> d.power?.let { PropRef(d.did, it.siid, it.piid) } }
        if (refs.isEmpty()) return devs
        val values = Flog.timed("prop/get x${refs.size}") { api.propGet(refs) }
        return devs.map { d ->
            // asBool 在逐项 code 非 0 时返回 null——设备离线时不把陈旧值当真值渲染。
            d.copy(
                on = values.firstOrNull {
                    it.did == d.did && it.siid == d.power?.siid && it.piid == d.power?.piid
                }?.asBool,
            )
        }
    }

    fun toggle(did: String) {
        val dev = _state.value.devices.firstOrNull { it.did == did } ?: return
        val power = dev.power ?: return
        val target = !(dev.on ?: false)

        // 乐观更新：链路可能要几秒，即时本地反馈是「跟手」和「坏了」的分界。
        update(did) { it.copy(on = target, busy = true) }

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ok = Flog.timed("prop/set $did ${power.siid}.${power.piid}=$target") {
                        api.setBool(did, power.siid, power.piid, target)
                    }
                    check(ok) { "写入被拒绝" }
                    delay(800)
                    readPower(listOf(dev)).first().on
                }
            }.onSuccess { actual ->
                update(did) { it.copy(on = actual, busy = false) }
            }.onFailure { e ->
                Flog.e("切换失败 $did", e)
                update(did) { it.copy(on = dev.on, busy = false) } // 回滚
                _state.value = _state.value.copy(error = "${dev.name}：${e.message}")
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

    private fun update(did: String, f: (Dev) -> Dev) {
        _state.value = _state.value.copy(
            devices = _state.value.devices.map { if (it.did == did) f(it) else it },
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

    private fun powerToggle(spec: SpecInstance): Control.Toggle? =
        spec.toControls(specs.translations(spec.type)).filterIsInstance<Control.Toggle>()
            .firstOrNull { it.isPower }

    private fun qrBitmap(data: String, size: Int = 360): Bitmap {
        val m = QRCodeWriter().encode(
            data, BarcodeFormat.QR_CODE, size, size,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                // 静默区压到 2 个模块：33mm 圆屏上每一个像素都要省
                EncodeHintType.MARGIN to 2,
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
