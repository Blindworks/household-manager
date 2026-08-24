package com.household.manager.network;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testet ausschliesslich die reine Berechnungslogik (Bytes+Nanosekunden -&gt; Mbit/s);
 * der Client selbst spricht das echte Netz an und wird bewusst nicht unit-getestet.
 * Package-private Zugriff auf {@link CloudflareSpeedtestClient#mbps}, da dieselbe Package.
 */
class CloudflareSpeedtestClientTest {

    @Test
    void tenMegabytesInOneSecond_isEightyMbps() {
        BigDecimal result = CloudflareSpeedtestClient.mbps(10_000_000L, 1_000_000_000L);

        assertThat(result).isEqualByComparingTo("80.00");
    }

    @Test
    void zeroElapsedNanos_returnsZero() {
        BigDecimal result = CloudflareSpeedtestClient.mbps(1_000_000L, 0L);

        assertThat(result).isEqualByComparingTo("0.00");
    }

    @Test
    void roundsToTwoDecimals() {
        BigDecimal result = CloudflareSpeedtestClient.mbps(125L, 1_000_000L);

        assertThat(result).isEqualByComparingTo("1.00");
    }
}
