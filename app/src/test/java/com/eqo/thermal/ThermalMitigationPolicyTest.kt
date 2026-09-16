package com.eqo.thermal

import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalMitigationPolicyTest {

    @Test
    fun `status NONE returns normal mitigation`() {
        val m = ThermalMitigationPolicy.evaluate(ThermalMitigationPolicy.STATUS_NONE)
        assertEquals(ThermalMitigationPolicy.DEFAULT_CADENCE, m.classifyCadence)
        assertEquals(0L, m.pacePollMs)
        assertEquals(1.0f, m.bitrateMultiplier, 0.001f)
        assertEquals("NORMAL", m.statusName)
    }

    @Test
    fun `status LIGHT throttles cadence and injects light pacing`() {
        val m = ThermalMitigationPolicy.evaluate(ThermalMitigationPolicy.STATUS_LIGHT)
        assertEquals(30, m.classifyCadence)
        assertEquals(2L, m.pacePollMs)
        assertEquals(1.0f, m.bitrateMultiplier, 0.001f)
        assertEquals("LIGHT", m.statusName)
    }

    @Test
    fun `status MODERATE throttles cadence to 60 and reduces bitrate`() {
        val m = ThermalMitigationPolicy.evaluate(ThermalMitigationPolicy.STATUS_MODERATE)
        assertEquals(60, m.classifyCadence)
        assertEquals(5L, m.pacePollMs)
        assertEquals(0.95f, m.bitrateMultiplier, 0.001f)
        assertEquals("MODERATE", m.statusName)
    }

    @Test
    fun `status SEVERE disables classifier and significantly reduces bitrate`() {
        val m = ThermalMitigationPolicy.evaluate(ThermalMitigationPolicy.STATUS_SEVERE)
        assertEquals(0, m.classifyCadence) // Disabled LiteRT worker
        assertEquals(10L, m.pacePollMs)
        assertEquals(0.85f, m.bitrateMultiplier, 0.001f)
        assertEquals("SEVERE", m.statusName)
    }

    @Test
    fun `status CRITICAL applies maximum pacing and bitrate cut`() {
        val m = ThermalMitigationPolicy.evaluate(ThermalMitigationPolicy.STATUS_CRITICAL)
        assertEquals(0, m.classifyCadence)
        assertEquals(20L, m.pacePollMs)
        assertEquals(0.75f, m.bitrateMultiplier, 0.001f)
        assertEquals("CRITICAL", m.statusName)
    }

    @Test
    fun `battery temperature elevates thermal status when platform listener lags`() {
        // Platform says NONE, but battery is 40.5C -> elevated to MODERATE
        val m1 = ThermalMitigationPolicy.evaluate(ThermalMitigationPolicy.STATUS_NONE, batteryTempCelsius = 40.5f)
        assertEquals("MODERATE", m1.statusName)
        assertEquals(60, m1.classifyCadence)

        // Platform says NONE, but battery is 42.5C -> elevated to SEVERE
        val m2 = ThermalMitigationPolicy.evaluate(ThermalMitigationPolicy.STATUS_NONE, batteryTempCelsius = 42.5f)
        assertEquals("SEVERE", m2.statusName)
        assertEquals(0, m2.classifyCadence)

        // Platform says SEVERE, but battery is 36C -> stays SEVERE
        val m3 = ThermalMitigationPolicy.evaluate(ThermalMitigationPolicy.STATUS_SEVERE, batteryTempCelsius = 36.0f)
        assertEquals("SEVERE", m3.statusName)
    }
}
