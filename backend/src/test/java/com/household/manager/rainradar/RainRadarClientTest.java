package com.household.manager.rainradar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RainRadarClientTest {

    @Test
    void fragtDreiMalDreiPixelVonZwanzigMinutenDavorBisGutZweiStundenDanachAn() {
        RainRadarClient client = new RainRadarClient(new RainRadarProperties(), new ObjectMapper());

        String uri = client.buildUri(50.05, 8.6, Instant.parse("2026-11-17T13:31:42Z")).toString();

        assertThat(uri).isEqualTo("https://api.brightsky.dev/radar?lat=50.05000&lon=8.60000&distance=1000"
                + "&format=plain&date=2026-11-17T13:11:00Z&last_date=2026-11-17T15:41:00Z");
    }
}
