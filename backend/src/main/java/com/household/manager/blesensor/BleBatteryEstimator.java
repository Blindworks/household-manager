package com.household.manager.blesensor;

/**
 * Batterieprozent aus der Spannung der ThermoBeacon-Knopfzelle, stueckweise linear —
 * dieselbe Kurve wie die Home-Assistant-Integration (thermobeacon-ble). Einzige Definition.
 */
public final class BleBatteryEstimator {

    private BleBatteryEstimator() {
    }

    public static Integer percent(Integer millivolts) {
        if (millivolts == null) {
            return null;
        }
        int mv = millivolts;
        double pct;
        if (mv >= 3000) {
            pct = 100;
        } else if (mv >= 2600) {
            pct = 60 + (mv - 2600) * 0.1;
        } else if (mv >= 2500) {
            pct = 40 + (mv - 2500) * 0.2;
        } else if (mv >= 2450) {
            pct = 20 + (mv - 2450) * 0.4;
        } else {
            pct = 0;
        }
        return (int) Math.round(pct);
    }
}
