package com.household.manager.tabletapp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorReporterTest {

    private fun seen(mac: String, ageMs: Long, now: Long) = SeenReading(
        ThermoBeaconReading(mac, 21.5625, 50.0, 3030), rssi = -60, seenAtElapsedMs = now - ageMs
    )

    @Test
    fun `Payload traegt tabletId, MAC, Werte, Spannung, RSSI und Alter in Sekunden`() {
        val now = 1_000_000L
        val json = SensorReporter.buildPayload("wandtablet", listOf(seen("aa:bb:cc:dd:ee:ff", 12_000, now)), now, 300_000)
        assertEquals(
            """{"tabletId":"wandtablet","readings":[{"mac":"aa:bb:cc:dd:ee:ff","temperature":21.5625,"humidity":50.0,"batteryMillivolts":3030,"rssi":-60,"ageSeconds":12}]}""",
            json
        )
    }

    @Test
    fun `Pakete aelter als fuenf Minuten fehlen`() {
        val now = 1_000_000L
        val json = SensorReporter.buildPayload("wandtablet",
            listOf(seen("aa:bb:cc:dd:ee:ff", 301_000, now), seen("11:22:33:44:55:66", 10_000, now)), now, 300_000)!!
        assertTrue(json.contains("11:22:33:44:55:66"))
        assertTrue(!json.contains("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun `leere Liste ergibt keinen Payload`() {
        assertNull(SensorReporter.buildPayload("wandtablet", emptyList(), 0, 300_000))
        assertNull(SensorReporter.buildPayload("wandtablet", listOf(seen("aa:bb:cc:dd:ee:ff", 400_000, 1_000_000)), 1_000_000, 300_000))
    }

    @Test
    fun `tabletId wird JSON-escaped`() {
        val json = SensorReporter.buildPayload("wand\"tablet", listOf(seen("aa:bb:cc:dd:ee:ff", 0, 10)), 10, 300_000)!!
        assertTrue(json.startsWith("""{"tabletId":"wand\"tablet""""))
    }
}
