package com.household.manager.tabletapp.ble

/** Ein geparstes ThermoBeacon-Advertisement. Batterie als Rohspannung; das Prozent rechnet das Backend. */
data class ThermoBeaconReading(
    val mac: String,
    val temperature: Double,
    val humidity: Double,
    val batteryMillivolts: Int
)
