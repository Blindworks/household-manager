package com.household.manager.tabletapp.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThermoBeaconParserTest {

    private val mac = "aa:bb:cc:dd:ee:ff"

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** HA-Fixture MFR_16_LEN_18: 28.31 °C, 40.44 %, 3.14 V. */
    private val mfr16 = bytes(0x00, 0x00, 0x9c, 0x0d, 0x00, 0x00, 0xfe, 0x44, 0x45, 0x0c, 0xc5, 0x01, 0x87, 0x02, 0x17, 0x00, 0x00, 0x00)

    /** HA-Fixture MFR_48: 21.56 °C, 53.56 %, 3.03 V. */
    private val mfr48 = bytes(0x00, 0x00, 0x8b, 0x01, 0x00, 0x00, 0x5d, 0x44, 0xd6, 0x0b, 0x59, 0x01, 0x59, 0x03, 0x44, 0x00, 0x0c, 0x00)

    /** HA-Fixture MFR_22: 20 Byte Payload = Metadatenpaket, keine Messwerte. */
    private val mfr21Meta = bytes(0x00, 0x00, 0xf0, 0x05, 0x00, 0x00, 0xd7, 0x6e, 0xbe, 0x01, 0x65, 0x00, 0x00, 0x00, 0xa7, 0x01, 0x00, 0x00, 0x00, 0x00)

    @Test
    fun `parst Lanyard-Hygrometer (ID 0x10)`() {
        val reading = ThermoBeaconParser.parse(mac, 0x10, mfr16)
        assertNotNull(reading)
        assertEquals(28.3125, reading!!.temperature, 0.0001)
        assertEquals(40.4375, reading.humidity, 0.0001)
        assertEquals(3141, reading.batteryMillivolts)
        assertEquals(mac, reading.mac)
    }

    @Test
    fun `parst Smart-Hygrometer (ID 0x30)`() {
        val reading = ThermoBeaconParser.parse(mac, 0x30, mfr48)
        assertNotNull(reading)
        assertEquals(21.5625, reading!!.temperature, 0.0001)
        assertEquals(53.5625, reading.humidity, 0.0001)
        assertEquals(3030, reading.batteryMillivolts)
    }

    @Test
    fun `negative Temperatur ist vorzeichenbehaftet`() {
        val payload = mfr48.copyOf()
        payload[10] = 0xe0.toByte(); payload[11] = 0xff.toByte()   // int16 -32 / 16 = -2.0
        assertEquals(-2.0, ThermoBeaconParser.parse(mac, 0x30, payload)!!.temperature, 0.0001)
    }

    @Test
    fun `Metadatenpaket mit 22 Byte Gesamtlaenge wird verworfen`() {
        assertNull(ThermoBeaconParser.parse(mac, 0x15, mfr21Meta))
    }

    @Test
    fun `unbekannte Hersteller-ID wird verworfen`() {
        assertNull(ThermoBeaconParser.parse(mac, 0x4c, mfr16))   // Apple
    }

    @Test
    fun `falsche Laenge wird verworfen`() {
        assertNull(ThermoBeaconParser.parse(mac, 0x10, mfr16.copyOf(17)))
        assertNull(ThermoBeaconParser.parse(mac, 0x10, mfr16 + bytes(0x00)))
    }

    @Test
    fun `Feuchte ueber 100 wird verworfen`() {
        val payload = mfr48.copyOf()
        payload[12] = 0x50.toByte(); payload[13] = 0x06.toByte()   // 0x0650 = 1616 / 16 = 101
        assertNull(ThermoBeaconParser.parse(mac, 0x30, payload))
    }
}
