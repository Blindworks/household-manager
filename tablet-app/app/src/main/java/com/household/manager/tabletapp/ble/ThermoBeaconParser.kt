package com.household.manager.tabletapp.ble

/**
 * Parser fuer das ThermoBeacon-Advertisement (SensorBlue, Brifit, Oria, ThermoPlus), nach den
 * Regeln der Home-Assistant-Integration thermobeacon-ble:
 *  - Hersteller-ID + Payload ergeben zusammen 20 Byte (22 = Metadatenpaket, wird verworfen)
 *  - Hersteller-ID muss eine bekannte Geraetekennung sein
 *  - Bytes 10..15 (gezaehlt inkl. der 2 ID-Bytes) little-endian: Spannung mV (u16), Temperatur/16 (i16), Feuchte/16 (u16)
 *  - Temperatur oder Feuchte > 100 => verwerfen
 * Reines Kotlin ohne Android-Abhaengigkeit, damit es im JVM-Unit-Test laeuft.
 */
object ThermoBeaconParser {

    const val SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"

    private const val DATA_PACKET_LENGTH = 20
    private val KNOWN_MANUFACTURER_IDS = setOf(0x10, 0x11, 0x14, 0x15, 0x18, 0x1B, 0x30)

    fun parse(mac: String, manufacturerId: Int, payload: ByteArray): ThermoBeaconReading? {
        if (manufacturerId !in KNOWN_MANUFACTURER_IDS) return null
        // HA stellt die ID als 2 Byte LE vor den Payload; Gesamtlaenge muss 20 sein
        if (payload.size + 2 != DATA_PACKET_LENGTH) return null

        // Offsets im kombinierten Paket 10..15 => im Payload 8..13
        val voltage = u16(payload, 8)
        val temperature = i16(payload, 10) / 16.0
        val humidity = u16(payload, 12) / 16.0
        if (temperature > 100 || humidity > 100) return null

        return ThermoBeaconReading(mac, temperature, humidity, voltage)
    }

    private fun u16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xff) or ((b[offset + 1].toInt() and 0xff) shl 8)

    private fun i16(b: ByteArray, offset: Int): Int = u16(b, offset).toShort().toInt()
}
