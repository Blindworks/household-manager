package com.household.manager.blesensor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BleBatteryEstimatorTest {

    @Test
    void vollAbDreiVolt() {
        assertThat(BleBatteryEstimator.percent(3000)).isEqualTo(100);
        assertThat(BleBatteryEstimator.percent(3140)).isEqualTo(100);
    }

    @Test
    void stueckweiseLinearWieHomeAssistant() {
        assertThat(BleBatteryEstimator.percent(2800)).isEqualTo(80);  // 60 + 200 * 0.1
        assertThat(BleBatteryEstimator.percent(2550)).isEqualTo(50);  // 40 + 50 * 0.2
        assertThat(BleBatteryEstimator.percent(2475)).isEqualTo(30);  // 20 + 25 * 0.4
        assertThat(BleBatteryEstimator.percent(2400)).isEqualTo(0);
    }

    @Test
    void nullBleibtNull() {
        assertThat(BleBatteryEstimator.percent(null)).isNull();
    }
}
