package com.household.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.WeatherConditions;
import com.household.manager.dto.WeatherForecastHour;
import com.household.manager.dto.WeatherOverviewResponse;
import com.household.manager.dto.WeatherWarning;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Ruft Wetterdaten der DWD-WarnWetter-App-API ab und parst sie.
 * Hält eine kurze Zwischenspeicherung (TTL), um die DWD-Server zu schonen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DwdWeatherService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final int FORECAST_HOURS = 24;
    private static final BigDecimal TENTH = BigDecimal.valueOf(10);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${dwd.base-url}")
    private String baseUrl;

    @Value("${dwd.station-id:10637}")
    private String stationId;

    @Value("${dwd.cache-ttl-ms:600000}")
    private long cacheTtlMs;

    private volatile WeatherOverviewResponse cached;
    private volatile long cachedAtMs;

    public synchronized WeatherOverviewResponse getOverview() {
        long now = Instant.now().toEpochMilli();
        if (cached != null && now - cachedAtMs < cacheTtlMs) {
            return cached;
        }
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("stationIds", stationId)
                .toUriString();
        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("DWD returned an empty response.");
        }
        WeatherOverviewResponse overview = parseOverview(json, stationId);
        cached = overview;
        cachedAtMs = now;
        return overview;
    }

    WeatherOverviewResponse parseOverview(String json, String station) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode stationNode = root.path(station);
            if (stationNode.isMissingNode()) {
                throw new IllegalStateException("DWD response missing station " + station);
            }

            JsonNode forecast1 = stationNode.path("forecast1");
            if (forecast1.isMissingNode()) {
                throw new IllegalStateException("DWD response missing forecast1 for station " + station);
            }
            long start = forecast1.path("start").asLong(stationNode.path("forecastStart").asLong());
            long step = forecast1.path("timeStep").asLong(3600000L);

            List<WeatherForecastHour> hours = buildHourly(forecast1, start, step);
            WeatherConditions current = hours.isEmpty() ? null
                    : toCurrent(forecast1, stationNode.path("days"), hours.get(0));
            LocalDateTime nextRain = findNextRain(hours);
            List<WeatherWarning> warnings = buildWarnings(stationNode.path("warnings"));

            return WeatherOverviewResponse.builder()
                    .stationId(station)
                    .current(current)
                    .hourlyForecast(hours)
                    .warnings(warnings)
                    .nextRain(nextRain)
                    .build();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse DWD response.", ex);
        }
    }

    private List<WeatherForecastHour> buildHourly(JsonNode forecast1, long start, long step) {
        JsonNode temps = forecast1.path("temperature");
        JsonNode precip = forecast1.path("precipitationTotal");
        JsonNode icons = forecast1.path("icon1h");
        int count = Math.min(temps.size(), FORECAST_HOURS);

        List<WeatherForecastHour> hours = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            hours.add(WeatherForecastHour.builder()
                    .time(toLocal(start + (long) i * step))
                    .temperature(scaleTenth(temps.path(i)))
                    .precipitation(scaleTenth(precip.path(i)))
                    .icon(icons.path(i).isMissingNode() ? null : icons.path(i).asInt())
                    .build());
        }
        return hours;
    }

    private WeatherConditions toCurrent(JsonNode forecast1, JsonNode days, WeatherForecastHour first) {
        JsonNode today = days.path(0);
        return WeatherConditions.builder()
                .time(first.getTime())
                .temperature(first.getTemperature())
                .precipitation(first.getPrecipitation())
                .windSpeed(scaleTenth(today.path("windSpeed")))
                .windDirection(directionDegrees(today.path("windDirection")))
                .humidity(scaleTenthToInt(forecast1.path("humidity").path(0)))
                .pressure(scaleTenth(forecast1.path("surfacePressure").path(0)))
                .icon(first.getIcon())
                .build();
    }

    private LocalDateTime findNextRain(List<WeatherForecastHour> hours) {
        for (WeatherForecastHour hour : hours) {
            if (hour.getPrecipitation() != null
                    && hour.getPrecipitation().compareTo(BigDecimal.ZERO) > 0) {
                return hour.getTime();
            }
        }
        return null;
    }

    private List<WeatherWarning> buildWarnings(JsonNode warningsNode) {
        List<WeatherWarning> warnings = new ArrayList<>();
        if (!warningsNode.isArray()) {
            return warnings;
        }
        for (JsonNode w : warningsNode) {
            warnings.add(WeatherWarning.builder()
                    .warnId(w.path("warnId").isMissingNode() ? null : w.path("warnId").asLong())
                    .event(w.path("event").asText(null))
                    .level(intOrNull(w.path("level")))
                    .headline(w.path("headline").asText(null))
                    .description(w.path("descriptionText").asText(null))
                    .instruction(w.path("instruction").asText(null))
                    .start(epochOrNull(w.path("start")))
                    .end(epochOrNull(w.path("end")))
                    .build());
        }
        return warnings;
    }

    private BigDecimal scaleTenth(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.decimalValue().divide(TENTH, 1, RoundingMode.HALF_UP);
    }

    private Integer scaleTenthToInt(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.decimalValue().divide(TENTH, 0, RoundingMode.HALF_UP).intValue();
    }

    private Integer directionDegrees(JsonNode node) {
        Integer raw = intOrNull(node);
        return raw == null ? null : Math.floorMod(raw, 360);
    }

    private Integer intOrNull(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull()) ? null : node.asInt();
    }

    private LocalDateTime toLocal(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDateTime();
    }

    private LocalDateTime epochOrNull(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull())
                ? null : toLocal(node.asLong());
    }
}
