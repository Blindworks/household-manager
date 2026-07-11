package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.AirQualityComponent;
import com.household.manager.dto.AirQualityOverviewResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UbaAirQualityServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UbaAirQualityService service =
            new UbaAirQualityService(null, objectMapper);

    private String sampleJson() {
        try (var in = getClass().getResourceAsStream("/uba-airquality-sample.json")) {
            if (in == null) {
                throw new IllegalStateException("uba-airquality-sample.json not found on test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Test
    void parsesLatestEntryWithOverallIndex() {
        AirQualityOverviewResponse result = service.parseAirQuality(sampleJson(), "636");

        assertThat(result.getStationId()).isEqualTo("636");
        assertThat(result.getDateTime()).isEqualTo(LocalDateTime.of(2026, 7, 11, 6, 0, 0));
        assertThat(result.getOverallIndex()).isEqualTo(1);
        assertThat(result.isIncomplete()).isFalse();
    }

    @Test
    void mapsAllComponentsWithSymbolAndUnit() {
        AirQualityOverviewResponse result = service.parseAirQuality(sampleJson(), "636");

        assertThat(result.getComponents())
                .extracting(AirQualityComponent::getCode)
                .containsExactlyInAnyOrder("O3", "NO2", "PM10", "PM2");

        AirQualityComponent ozone = result.getComponents().stream()
                .filter(c -> "O3".equals(c.getCode()))
                .findFirst().orElseThrow();
        assertThat(ozone.getSymbol()).isEqualTo("O₃");
        assertThat(ozone.getName()).isEqualTo("Ozon");
        assertThat(ozone.getUnit()).isEqualTo("µg/m³");
        assertThat(ozone.getValue()).isEqualByComparingTo("61");
        assertThat(ozone.getIndex()).isEqualTo(1);
    }

    @Test
    void picksMostRecentAcrossMultipleEntries() {
        String json = "{\"data\":{\"636\":{"
                + "\"2026-07-11 05:00:00\":[\"2026-07-11 06:00:00\",0,0,[1,9,0,\"0.45\"]],"
                + "\"2026-07-11 06:00:00\":[\"2026-07-11 07:00:00\",3,1,[3,180,3,\"3\"]]"
                + "}}}";

        AirQualityOverviewResponse result = service.parseAirQuality(json, "636");

        assertThat(result.getDateTime()).isEqualTo(LocalDateTime.of(2026, 7, 11, 6, 0, 0));
        assertThat(result.getOverallIndex()).isEqualTo(3);
        assertThat(result.isIncomplete()).isTrue();
        assertThat(result.getComponents()).hasSize(1);
        assertThat(result.getComponents().get(0).getCode()).isEqualTo("O3");
    }

    @Test
    void throwsWhenStationDataMissing() {
        assertThatThrownBy(() -> service.parseAirQuality("{\"data\":{}}", "636"))
                .isInstanceOf(IllegalStateException.class);
    }
}
