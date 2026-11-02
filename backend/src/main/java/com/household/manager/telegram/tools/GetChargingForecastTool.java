package com.household.manager.telegram.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.ai.AgentTool;
import com.household.manager.charging.ChargePointStatus;
import com.household.manager.charging.ChargingFavoriteService;
import com.household.manager.charging.ChargingSnapshot;
import com.household.manager.charging.ChargingStationDetails;
import com.household.manager.charging.forecast.ChargingForecast;
import com.household.manager.charging.forecast.ChargingForecastService;
import com.household.manager.charging.forecast.ChargingForecastSettings;
import com.household.manager.charging.forecast.ChargingPatternService;
import com.household.manager.charging.forecast.HessenHolidays;
import com.household.manager.model.entity.ChargingFavorite;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Vorhersage für eine favorisierte Ladesäule: frei bei Ankunft, Restdauer, typische Belegung. */
@Component
@RequiredArgsConstructor
public class GetChargingForecastTool implements AgentTool {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final long NEAR_LIMIT_MINUTES = 60;

    private final ChargingFavoriteService favoriteService;
    private final ChargingForecastService forecastService;
    private final ChargingSnapshot snapshot;
    private final ChargingPatternService patternService;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public String name() {
        return "get_charging_forecast";
    }

    @Override
    public String description() {
        return "Vorhersage fuer eine favorisierte Ladesaeule: Wahrscheinlichkeit, dass in 15/30/60 Minuten "
                + "mindestens ein Ladepunkt frei ist, voraussichtliche Restdauer belegter Punkte und optional "
                + "die typische Belegung zu einer Uhrzeit.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "station", Map.of("type", "string",
                                "description", "Name oder Teil des Namens einer favorisierten Ladesaeule"),
                        "at", Map.of("type", "string",
                                "description", "Optionale Ankunftszeit als HH:mm (heute, sonst morgen)")),
                "required", List.of("station"));
    }

    @Override
    public String execute(Map<String, Object> input) throws Exception {
        List<ChargingFavorite> favorites = favoriteService.list();
        String query = String.valueOf(input.getOrDefault("station", "")).trim().toLowerCase(Locale.ROOT);
        List<ChargingFavorite> matches = favorites.stream()
                .filter(f -> f.getDisplayName() != null
                        && f.getDisplayName().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        if (query.isEmpty() || matches.size() != 1) {
            String problem = matches.isEmpty() ? "Keine Ladesaeule gefunden." : "Mehrere Ladesaeulen passen.";
            return json.writeValueAsString(Map.of("error", problem,
                    "favorites", favorites.stream().map(ChargingFavorite::getDisplayName).toList()));
        }

        Optional<LocalTime> at = Optional.empty();
        Object rawAt = input.get("at");
        if (rawAt != null && !String.valueOf(rawAt).isBlank()) {
            try {
                at = Optional.of(LocalTime.parse(String.valueOf(rawAt).trim(), HH_MM));
            } catch (DateTimeParseException e) {
                return json.writeValueAsString(Map.of("error", "Ungueltige Uhrzeit, erwartet HH:mm."));
            }
        }
        return json.writeValueAsString(describe(matches.get(0), at));
    }

    private Map<String, Object> describe(ChargingFavorite favorite, Optional<LocalTime> at) {
        String stationId = favorite.getStationId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("station", favorite.getDisplayName());
        snapshot.details(stationId).ifPresent(details -> addCounts(out, details));

        Optional<ChargingForecast> forecast = forecastService.latest(stationId);
        if (forecast.isEmpty()) {
            out.put("status", "no_forecast");
            return out;
        }
        ChargingForecast f = forecast.get();
        out.put("status", f.status() == ChargingForecast.Status.OK ? "ok" : "insufficient_data");
        out.put("observedDays", f.observedDays());
        out.put("freeProbabilityPercent", f.freeProbabilityPercent());
        out.put("points", f.points().stream().map(this::point).toList());
        at.ifPresent(time -> addArrival(out, stationId, f, time));
        return out;
    }

    private void addCounts(Map<String, Object> out, ChargingStationDetails details) {
        long free = details.chargePoints().stream().filter(p -> p.status() == ChargePointStatus.FREE).count();
        long total = details.chargePoints().stream()
                .filter(p -> p.status() == ChargePointStatus.FREE || p.status() == ChargePointStatus.OCCUPIED)
                .count();
        out.put("free", free);
        out.put("total", total);
    }

    private Map<String, Object> point(ChargingForecast.PointForecast p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chargePointId", p.chargePointId());
        m.put("occupiedSince", time(p.occupiedSince()));
        m.put("expectedFreeAt", time(p.expectedFreeAt()));
        m.put("expectedFreeLatest", time(p.expectedFreeLatest()));
        return m;
    }

    private void addArrival(Map<String, Object> out, String stationId, ChargingForecast f, LocalTime time) {
        ZonedDateTime now = clock.instant().atZone(clock.getZone());
        ZonedDateTime target = now.toLocalDate().atTime(time).atZone(clock.getZone());
        if (target.isBefore(now)) {
            target = target.plusDays(1);
        }
        long minutes = (long) Math.ceil(Duration.between(now, target).toSeconds() / 60.0);
        if (minutes <= NEAR_LIMIT_MINUTES) {
            ChargingForecastSettings.HORIZONS_MINUTES.stream().filter(h -> h >= minutes).findFirst()
                    .map(f.freeProbabilityPercent()::get)
                    .ifPresent(p -> out.put("freeProbabilityAtPercent", p));
            return;
        }
        int hour = target.getHour();
        Double occupancy = patternService.pattern(stationId).occupancy()
                [HessenHolidays.heatmapDay(target.toLocalDate())][hour];
        out.put("atHour", hour);
        out.put("typicalOccupancyPercent", occupancy == null ? null : (Object) Math.round(occupancy * 100));
    }

    private String time(Instant instant) {
        return instant == null ? null : instant.atZone(clock.getZone()).format(HH_MM);
    }
}
