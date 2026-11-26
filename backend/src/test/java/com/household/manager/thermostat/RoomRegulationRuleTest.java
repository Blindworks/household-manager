package com.household.manager.thermostat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomRegulationRuleTest {

    @Test
    void kalterRaumMitKaltemHeizkoerperOeffnetDasVentilDeutlich() {
        // Realfall Buero 2026-11-26: Raum 19,8 statt 21, Heizkoerper 17 => 17 + 3 x 1,2 = 20,6
        assertEquals(20.5, RoomRegulationRule.setpoint(21.0, 19.8, 17.0));
    }

    @Test
    void zuWarmerRaumSchliesstDasVentilAuchBeiKaltemHeizkoerper() {
        double setpoint = RoomRegulationRule.setpoint(21.0, 22.0, 17.0);
        assertEquals(14.0, setpoint);
        assertTrue(setpoint < 17.0);
    }

    @Test
    void imZielBleibtDerSollwertAufDemHeizkoerperWert() {
        assertEquals(23.0, RoomRegulationRule.setpoint(21.0, 21.0, 23.0));
    }

    @Test
    void rundetAufHalbeGrad() {
        assertEquals(22.5, RoomRegulationRule.setpoint(21.0, 20.8, 21.7));
        assertEquals(22.0, RoomRegulationRule.setpoint(21.0, 20.8, 21.3));
    }

    @Test
    void begrenztDenAntriebUmDenHeizkoerperWert() {
        assertEquals(25.0, RoomRegulationRule.setpoint(21.0, 14.0, 20.0));
        assertEquals(15.0, RoomRegulationRule.setpoint(21.0, 26.0, 20.0));
    }

    @Test
    void bleibtImmerUeberDemAusBereichUndUnter30() {
        assertEquals(5.5, RoomRegulationRule.setpoint(6.0, 10.0, 6.0));
        assertEquals(30.0, RoomRegulationRule.setpoint(28.0, 20.0, 29.0));
    }
}
