package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.charging.ChargePoint;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingFavoriteService;
import com.household.manager.charging.ChargingSnapshot;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.charging.forecast.ChargingForecast;
import com.household.manager.charging.forecast.ChargingForecastService;
import com.household.manager.charging.forecast.ChargingPatternService;
import com.household.manager.charging.forecast.StationPattern;
import com.household.manager.model.entity.ChargingFavorite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetChargingForecastToolTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChargingFavoriteService favorites = mock(ChargingFavoriteService.class);
    private final ChargingForecastService forecasts = mock(ChargingForecastService.class);
    private final ChargingSnapshot snapshot = mock(ChargingSnapshot.class);
    private final ChargingPatternService patterns = mock(ChargingPatternService.class);
    private GetChargingForecastTool tool;

    @BeforeEach
    void setUp() {
        // Montag, 2026-11-02, 17:15 Berlin
        Clock clock = Clock.fixed(java.time.LocalDateTime.of(2026, 11, 2, 17, 15).atZone(BERLIN).toInstant(), BERLIN);
        tool = new GetChargingForecastTool(favorites, forecasts, snapshot, patterns, clock);
        when(favorites.list()).thenReturn(List.of(favorite("1", "Rewe Hauptstraße"), favorite("2", "Aral Ring")));
    }

    private ChargingFavorite favorite(String id, String name) {
        return ChargingFavorite.builder().stationId(id).displayName(name).build();
    }

    private ChargingForecast okForecast() {
        return new ChargingForecast(ChargingForecast.Status.OK, 30, Map.of(15, 40, 30, 55, 60, 70),
                List.of(new ChargingForecast.PointForecast("p1",
                        java.time.LocalDateTime.of(2026, 11, 2, 16, 0).atZone(BERLIN).toInstant(),
                        java.time.LocalDateTime.of(2026, 11, 2, 17, 45).atZone(BERLIN).toInstant(),
                        java.time.LocalDateTime.of(2026, 11, 2, 18, 30).atZone(BERLIN).toInstant())),
                null, null, new Double[7][24]);
    }

    private JsonNode run(Map<String, Object> input) throws Exception {
        return JSON.readTree(tool.execute(input));
    }

    @Test
    void findetFavoritPerTeilname() throws Exception {
        when(forecasts.latest("1")).thenReturn(Optional.of(okForecast()));
        when(snapshot.details("1")).thenReturn(Optional.of(new ChargingStationDetails("1", List.of(
                new ChargePoint("p1", ChargePointStatus.OCCUPIED, 50.0, "CCS", null, null),
                new ChargePoint("p2", ChargePointStatus.FREE, 50.0, "CCS", null, null)))));

        JsonNode result = run(Map.of("station", "rewe"));

        assertEquals("Rewe Hauptstraße", result.get("station").asText());
        assertEquals("ok", result.get("status").asText());
        assertEquals(1, result.get("free").asInt());
        assertEquals(2, result.get("total").asInt());
        assertEquals(30, result.get("observedDays").asInt());
        assertEquals(55, result.get("freeProbabilityPercent").get("30").asInt());
        assertEquals("17:45", result.get("points").get(0).get("expectedFreeAt").asText());
    }

    @Test
    void mehrdeutigListetFavoriten() throws Exception {
        JsonNode result = run(Map.of("station", "r"));

        assertTrue(result.has("error"));
        assertEquals(2, result.get("favorites").size());
    }

    @Test
    void leereStationBittetUmAngabeUndListetFavoriten() throws Exception {
        JsonNode result = run(Map.of("station", "   "));

        assertEquals("Bitte eine Ladesaeule angeben.", result.get("error").asText());
        assertEquals(2, result.get("favorites").size());
    }

    @Test
    void fehlendeStationBittetUmAngabe() throws Exception {
        java.util.Map<String, Object> input = new java.util.HashMap<>();
        input.put("station", null);

        JsonNode result = run(input);

        assertEquals("Bitte eine Ladesaeule angeben.", result.get("error").asText());
        assertEquals(2, result.get("favorites").size());
    }

    @Test
    void zuWenigDatenWirdGemeldet() throws Exception {
        when(forecasts.latest("2")).thenReturn(Optional.of(new ChargingForecast(
                ChargingForecast.Status.INSUFFICIENT_DATA, 3, Map.of(), List.of(), null, null, new Double[7][24])));

        JsonNode result = run(Map.of("station", "aral"));

        assertEquals("insufficient_data", result.get("status").asText());
        assertEquals(3, result.get("observedDays").asInt());
    }

    @Test
    void spaeteZeitNutztMuster() throws Exception {
        when(forecasts.latest("1")).thenReturn(Optional.of(okForecast()));
        Double[][] occupancy = new Double[7][24];
        occupancy[0][20] = 0.734;
        when(patterns.pattern(anyString())).thenReturn(new StationPattern(Duration.ofDays(30), occupancy,
                new double[7][24], 0.0, List.of()));

        JsonNode result = run(Map.of("station", "rewe", "at", "20:00"));

        assertEquals(20, result.get("atHour").asInt());
        assertEquals(73, result.get("typicalOccupancyPercent").asInt());
    }

    @Test
    void naheZeitNutztHorizont() throws Exception {
        when(forecasts.latest("1")).thenReturn(Optional.of(okForecast()));

        JsonNode result = run(Map.of("station", "rewe", "at", "17:40"));

        assertEquals(55, result.get("freeProbabilityAtPercent").asInt());
    }

    @Test
    void ungueltigeZeitLiefertFehler() throws Exception {
        JsonNode result = run(Map.of("station", "rewe", "at", "abends"));

        assertTrue(result.has("error"));
    }
}
