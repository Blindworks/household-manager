package com.household.manager.laundry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DryingCalibrationTest {

    private static DryingSample confirmed(double x) {
        return new DryingSample(SampleKind.CONFIRMED, x);
    }

    private static DryingSample weak(double x) {
        return new DryingSample(SampleKind.WEAK, x);
    }

    private static DryingSample lowerBound(double x) {
        return new DryingSample(SampleKind.LOWER_BOUND, x);
    }

    @Test
    void ohneProbenGiltDerStartwert() {
        assertEquals(17.5, DryingCalibration.requiredLoad(List.of()), 1e-9);
    }

    @Test
    void medianDerBestaetigten() {
        assertEquals(12.0, DryingCalibration.requiredLoad(List.of(
                List.of(confirmed(10)), List.of(confirmed(12)), List.of(confirmed(30)))), 1e-9);
    }

    @Test
    void schwacheProbenNurUnterDreiBestaetigten() {
        assertEquals(12.0, DryingCalibration.requiredLoad(List.of(
                List.of(confirmed(10)), List.of(confirmed(12)), List.of(weak(20)))), 1e-9);
        assertEquals(11.0, DryingCalibration.requiredLoad(List.of(
                List.of(confirmed(10)), List.of(confirmed(12)), List.of(confirmed(11)),
                List.of(weak(40)))), 1e-9);
    }

    @Test
    void untergrenzeHebtNurAusWaschgaengenOhneBestaetigung() {
        assertEquals(25.0, DryingCalibration.requiredLoad(List.of(
                List.of(confirmed(10)), List.of(lowerBound(20), lowerBound(25)),
                List.of(lowerBound(99), confirmed(12)))), 1e-9);
    }

    @Test
    void nurUntergrenzenNehmenMindestensDenStartwert() {
        assertEquals(17.5, DryingCalibration.requiredLoad(List.of(List.of(lowerBound(5)))), 1e-9);
        assertEquals(30.0, DryingCalibration.requiredLoad(List.of(List.of(lowerBound(30)))), 1e-9);
    }

    @Test
    void nurDieLetztenZehnWaschgaenge() {
        List<List<DryingSample>> sessions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sessions.add(List.of(confirmed(10)));
        }
        for (int i = 0; i < 5; i++) {
            sessions.add(List.of(confirmed(50)));
        }
        assertEquals(10.0, DryingCalibration.requiredLoad(sessions), 1e-9);
    }
}
