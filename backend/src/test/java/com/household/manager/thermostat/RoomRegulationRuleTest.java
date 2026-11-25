package com.household.manager.thermostat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomRegulationRuleTest {

    @Test
    void verschiebtDenSollwertUmDenVersatzZwischenHeizkoerperUndRaum() {
        // Heizkoerper misst 23, Raum 20: Versatz 3 => Thermostat-Soll 21 + 3
        assertEquals(24.0, RoomRegulationRule.setpoint(21.0, 20.0, 23.0));
    }

    @Test
    void rundetAufHalbeGrad() {
        assertEquals(22.5, RoomRegulationRule.setpoint(21.0, 20.1, 21.7));
        assertEquals(22.0, RoomRegulationRule.setpoint(21.0, 20.1, 21.3));
    }

    @Test
    void begrenztDenVersatzNachObenUndUnten() {
        assertEquals(26.0, RoomRegulationRule.setpoint(21.0, 14.0, 28.0));
        assertEquals(19.0, RoomRegulationRule.setpoint(21.0, 26.0, 20.0));
    }

    @Test
    void bleibtImmerUeberDemAusBereichUndUnter30() {
        assertEquals(5.5, RoomRegulationRule.setpoint(6.0, 10.0, 6.0));
        assertEquals(30.0, RoomRegulationRule.setpoint(28.0, 20.0, 30.0));
    }
}
