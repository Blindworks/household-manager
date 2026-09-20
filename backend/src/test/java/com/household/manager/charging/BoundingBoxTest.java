package com.household.manager.charging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BoundingBoxTest {

    @Test
    void umschliesstDenKreisMitBreitengradabhaengigerLaengenspanne() {
        BoundingBox box = BoundingBox.around(50.0, 8.0, 10.0);

        assertThat(box.toLat() - box.fromLat()).isCloseTo(0.1797, within(0.001));
        // Bei 50° N ist ein Laengengrad nur cos(50°) ≈ 0,643 so lang wie ein Breitengrad.
        assertThat(box.toLon() - box.fromLon()).isCloseTo(0.2795, within(0.002));
    }
}
