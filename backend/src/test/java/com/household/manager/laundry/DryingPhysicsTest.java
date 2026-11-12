package com.household.manager.laundry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DryingPhysicsTest {

    @Test
    void vpdBei16GradUnd60ProzentIstEtwa073() {
        assertEquals(0.73, DryingPhysics.vaporPressureDeficitKpa(16, 60), 0.01);
    }

    @Test
    void vpdBei100ProzentIstNull() {
        assertEquals(0.0, DryingPhysics.vaporPressureDeficitKpa(16, 100), 1e-9);
    }

    @Test
    void vpdUeber100ProzentIstNieNegativ() {
        assertEquals(0.0, DryingPhysics.vaporPressureDeficitKpa(16, 110), 1e-9);
    }

    @Test
    void restzeitOhneTrockenleistungIstLeer() {
        assertTrue(DryingPhysics.remainingHours(17.5, 3, 0).isEmpty());
    }

    @Test
    void restzeitRechnetLinear() {
        assertEquals(10.0, DryingPhysics.remainingHours(17.5, 10.2, 0.73).getAsDouble(), 0.1);
    }

    @Test
    void restzeitIstNullWennErreicht() {
        assertEquals(0.0, DryingPhysics.remainingHours(17.5, 20, 0.73).getAsDouble(), 1e-9);
    }
}
