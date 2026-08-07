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

    private fun svc(iid: Int, cat: String, props: List<SpecProperty>) = SpecService(
        iid = iid,
        type = "urn:miot-spec-v2:service:$cat:00000001:xiaomi-x:1",
        description = cat,
        properties = props,
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
