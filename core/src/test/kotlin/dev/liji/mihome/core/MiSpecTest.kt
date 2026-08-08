package dev.liji.mihome.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用真实 spec 的形状构造夹具（取自 miot-spec.org 上 xiaomi-lamp35 与 lumi-mcn02 的实际结构），
 * 不联网。锁住两条容易悄悄回归的规则：主服务判定，和「on 只在主服务里算常用」。
 */
class MiSpecTest {

    private fun prop(
        iid: Int,
        cat: String,
        format: String = "bool",
        access: List<String> = listOf("read", "write", "notify"),
        range: List<Double>? = null,
        values: List<SpecValue>? = null,
        unit: String? = null,
    ) = SpecProperty(
        iid = iid,
        type = "urn:miot-spec-v2:property:$cat:00000001:xiaomi-x:1",
        description = cat,
        format = format,
        access = access,
        unit = unit,
        valueList = values,
        valueRange = range,
    )

    private fun svc(iid: Int, cat: String, props: List<SpecProperty>, actions: List<SpecAction> = emptyList()) =
        SpecService(
            iid = iid,
            type = "urn:miot-spec-v2:service:$cat:00000001:xiaomi-x:1",
            description = cat,
            properties = props,
            actions = actions,
        )

    private fun act(iid: Int, cat: String, inputs: List<Int> = emptyList()) = SpecAction(
        iid = iid,
        type = "urn:miot-spec-v2:action:$cat:00000001:xiaomi-x:1",
        description = cat,
        inputs = inputs,
    )

    /** 桌灯：主服务 light(siid 2) 只有 on + brightness；siid 6 是 focus-mode，也带一个 on。 */
    private val lamp = SpecInstance(
        type = "urn:miot-spec-v2:device:light:0000A001:xiaomi-lamp35:1:0000C801",
        services = listOf(
            svc(1, "device-information", listOf(prop(1, "manufacturer", "string", listOf("read")))),
            svc(2, "light", listOf(
                prop(1, "on"),
                prop(2, "brightness", "uint8", range = listOf(1.0, 100.0, 1.0), unit = "percentage"),
            )),
            svc(6, "focus-mode", listOf(
                prop(1, "on"),
                prop(2, "focus-time", "uint8", range = listOf(1.0, 90.0, 1.0), unit = "minutes"),
            )),
        ),
    )

    /** 空调：主服务 air-conditioner(siid 2)，风机档位在非主服务 siid 3。 */
    private val ac = SpecInstance(
        type = "urn:miot-spec-v2:device:air-conditioner:0000A004:lumi-mcn02:1",
        services = listOf(
            svc(2, "air-conditioner", listOf(
                prop(1, "on"),
                prop(3, "target-temperature", "float", range = listOf(16.0, 30.0, 1.0), unit = "celsius"),
            )),
            svc(3, "fan-control", listOf(
                prop(1, "fan-level", "uint8", values = listOf(SpecValue(0, "Auto"), SpecValue(1, "Low"))),
                prop(2, "vertical-swing"),
            )),
        ),
    )

    /**
     * 扫地机：无入参 start-sweep、单入参（枚举）set-suction、
     * 单入参但无约束的 goto-room（丢弃）、多入参的 timed-clean（丢弃）。
     */
    private val vacuum = SpecInstance(
        type = "urn:miot-spec-v2:device:vacuum:0000A006:dreame-p2008:1",
        services = listOf(
            svc(
                2, "vacuum",
                props = listOf(
                    prop(1, "status", "uint8", listOf("read"), values = listOf(SpecValue(1, "扫地中"))),
                    prop(
                        2, "suction-level", "uint8", listOf("read"),
                        values = listOf(SpecValue(0, "安静"), SpecValue(1, "标准"), SpecValue(2, "强力")),
                    ),
                    prop(3, "room-id", "uint8", listOf()), // 既无 value-list 也无 value-range
                ),
                actions = listOf(
                    act(1, "start-sweep"),
                    act(2, "set-suction", inputs = listOf(2)),
                    act(3, "goto-room", inputs = listOf(3)),
                    act(4, "timed-clean", inputs = listOf(2, 3)),
                ),
            ),
        ),
    )

    @Test
    fun arglessActionSurvivesWithNullArg() {
        val a = vacuum.toControls().filterIsInstance<Control.Act>().single { it.aiid == 1 }
        assertNull(a.arg)
        assertTrue(a.quick)
    }

    /** v3.1 的核心：单入参动作按入参属性的 value-list 生成可选档。 */
    @Test
    fun singleInputActionCarriesEnumOptions() {
        val a = vacuum.toControls().filterIsInstance<Control.Act>().single { it.aiid == 2 }
        val arg = a.arg ?: error("set-suction 应带入参描述")
        assertEquals(2, arg.piid)
        assertEquals(listOf(0 to "安静", 1 to "标准", 2 to "强力"), arg.options)
    }

    /** 入参没有任何约束就没法在表上选，丢弃；多入参同样丢弃。 */
    @Test
    fun unconstrainedAndMultiInputActionsAreDropped() {
        val acts = vacuum.toControls().filterIsInstance<Control.Act>().map { it.aiid }
        assertTrue(3 !in acts, "无约束入参的动作应被丢弃")
        assertTrue(4 !in acts, "多入参动作应被丢弃")
    }

    @Test
    fun rangeOptionsEnumerateOrSample() {
        // 4 档全列
        assertEquals(listOf(1, 2, 3, 4), rangeOptions(1.0, 4.0, 1.0, null).map { it.first })
        // 101 档采样成 8 个，两端必在
        val sampled = rangeOptions(0.0, 100.0, 1.0, "percentage").map { it.first }
        assertEquals(8, sampled.size)
        assertEquals(0, sampled.first())
        assertEquals(100, sampled.last())
        // 非整数范围不可枚举
        assertTrue(rangeOptions(0.5, 1.5, 0.1, null).isEmpty())
        // 量程不是步进整倍数时，采样的最后一档不得越过 max（0–101 步 3 曾采出 102）
        val ragged = rangeOptions(0.0, 101.0, 3.0, null).map { it.first }
        assertTrue(ragged.all { it <= 101 }, "采样值越界: $ragged")
        assertEquals(99, ragged.last())
        // 覆盖不到标准色温档的量程要落回普通枚举，不能返回空
        assertTrue(rangeOptions(2000.0, 2600.0, 100.0, "kelvin").isNotEmpty())
    }

    /** 弹层档位：色温走 4 个标准档，普通整数范围走枚举/采样，不再是空白板。 */
    @Test
    fun rangePresetsNotEmptyForNonKelvin() {
        val temp = ac.toControls().filterIsInstance<Control.Range>().single { it.siid == 2 && it.piid == 3 }
        assertEquals((16..30).toList(), temp.presets().map { it.first })
        val kelvin = Control.Range(2, 3, "色温", true, true, 0, 2700.0, 6500.0, 1.0, "kelvin")
        assertEquals(listOf(2700, 3500, 5000, 6500), kelvin.presets().map { it.first })
    }

    @Test
    fun powerComesFromPrimaryServiceOnly() {
        val power = lamp.toControls().filterIsInstance<Control.Toggle>().filter { it.isPower }
        assertEquals(1, power.size, "应当只有一个电源开关")
        assertEquals(2, power[0].siid)
        assertEquals(1, power[0].piid)
    }

    /** 回归：focus-mode 的 on 曾经因为「类别是 on」被误标成常用，表上会多出一个开关。 */
    @Test
    fun focusModeOnIsNeitherPowerNorQuick() {
        val c = lamp.toControls().filterIsInstance<Control.Toggle>().single { it.siid == 6 && it.piid == 1 }
        assertTrue(!c.isPower, "focus-mode 的 on 不能是电源")
        assertTrue(!c.quick, "focus-mode 的 on 不能进常用集合")
        assertTrue(!c.primary)
    }

    /** 非 on 的类别在非主服务里仍算常用——空调风机档位就在 siid 3。 */
    @Test
    fun nonPrimaryFanLevelStaysQuick() {
        val fan = ac.toControls().filterIsInstance<Control.Choice>().single { it.siid == 3 && it.piid == 1 }
        assertTrue(fan.quick)
        assertTrue(!fan.primary)
        val swing = ac.toControls().filterIsInstance<Control.Toggle>().single { it.siid == 3 && it.piid == 2 }
        assertTrue(swing.quick)
    }

    /** 桌灯没有色温：spec 里没这个属性，控件就不该被造出来（不硬编码 piid 的意义所在）。 */
    @Test
    fun absentPropertyProducesNoControl() {
        assertNull(lamp.toControls().find { it.label.contains("color-temperature") })
    }

    @Test
    fun deviceInformationServiceIsDropped() {
        assertTrue(lamp.toControls().none { it.siid == 1 })
    }

    @Test
    fun quickControlsComeFirstAndPowerLeads() {
        val cs = ac.toControls()
        val firstNonQuick = cs.indexOfFirst { !it.quick }
        if (firstNonQuick >= 0) {
            assertTrue(cs.take(firstNonQuick).all { it.quick }, "常用控件必须排在前面")
        }
        assertTrue((cs.first() as Control.Toggle).isPower, "电源开关排第一")
    }

    @Test
    fun rangeCarriesBoundsAndUnit() {
        val temp = ac.toControls().filterIsInstance<Control.Range>().single { it.siid == 2 && it.piid == 3 }
        assertEquals(16.0, temp.min)
        assertEquals(30.0, temp.max)
        assertEquals(1.0, temp.step)
        assertEquals("celsius", temp.unit)
    }

    /**
     * 主服务判定就靠这个：设备 urn 与服务 urn 的第 3 段是同一个词，
     * 所以 device:light 能对上 service:light。
     */
    @Test
    fun urnCategoryParsing() {
        assertEquals("light", "urn:miot-spec-v2:device:light:0000A001:linp-lx2bcw:1".urnCategory())
        assertEquals("light", "urn:miot-spec-v2:service:light:00007802:linp-lx2bcw:1".urnCategory())
        assertEquals("on", "urn:miot-spec-v2:property:on:00000006:linp-lx2bcw:1".urnCategory())
        assertEquals(
            "air-conditioner",
            "urn:miot-spec-v2:device:air-conditioner:0000A004:lumi-mcn02:1".urnCategory(),
        )
        assertNull("nonsense".urnCategory())
    }
}
