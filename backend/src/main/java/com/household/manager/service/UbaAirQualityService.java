package com.household.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.AirQualityComponent;
import com.household.manager.dto.AirQualityOverviewResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Ruft den Luftqualitätsindex des Umweltbundesamtes (UBA) ab und parst ihn.
 * Der DWD liefert keine Luftqualität; offizielle Quelle ist das UBA.
 * Kurzer TTL-Cache schont die UBA-Server; keine Persistenz.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UbaAirQualityService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter UBA_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Statisches Komponenten-Mapping aus den UBA-Metadaten (components/json). */
    private static final Map<Integer, ComponentMeta> COMPONENTS = Map.ofEntries(
            Map.entry(1, new ComponentMeta("PM10", "PM₁₀", "Feinstaub", "µg/m³")),
            Map.entry(2, new ComponentMeta("CO", "CO", "Kohlenmonoxid", "mg/m³")),
            Map.entry(3, new ComponentMeta("O3", "O₃", "Ozon", "µg/m³")),
            Map.entry(4, new ComponentMeta("SO2", "SO₂", "Schwefeldioxid", "µg/m³")),
            Map.entry(5, new ComponentMeta("NO2", "NO₂", "Stickstoffdioxid", "µg/m³")),
            Map.entry(6, new ComponentMeta("PM10PB", "Pb", "Blei im Feinstaub", "µg/m³")),
            Map.entry(7, new ComponentMeta("PM10BAP", "BaP", "Benzo(a)pyren im Feinstaub", "ng/m³")),
            Map.entry(8, new ComponentMeta("CHB", "C₆H₆", "Benzol", "µg/m³")),
            Map.entry(9, new ComponentMeta("PM2", "PM₂,₅", "Feinstaub", "µg/m³")),
            Map.entry(10, new ComponentMeta("PM10AS", "As", "Arsen im Feinstaub", "ng/m³")),
            Map.entry(11, new ComponentMeta("PM10CD", "Cd", "Cadmium im Feinstaub", "ng/m³")),
            Map.entry(12, new ComponentMeta("PM10NI", "Ni", "Nickel im Feinstaub", "µg/m³"))
    );

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${uba.base-url}")
    private String baseUrl;

    @Value("${uba.station-id:636}")
    private String stationId;

    @Value("${uba.cache-ttl-ms:600000}")
    private long cacheTtlMs;

    private volatile AirQualityOverviewResponse cached;
    private volatile long cachedAtMs;

    public synchronized AirQualityOverviewResponse getOverview() {
        long now = Instant.now().toEpochMilli();
        if (cached != null && now - cachedAtMs < cacheTtlMs) {
            return cached;
        }
        LocalDate today = LocalDate.now(ZONE);
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("date_from", today.minusDays(1))
                .queryParam("time_from", 1)
                .queryParam("date_to", today)
                .queryParam("time_to", 24)
                .queryParam("station", stationId)
                .queryParam("lang", "de")
                .toUriString();
        String json = restTemplate.getForObject(url, String.class);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("UBA returned an empty response.");
        }
        AirQualityOverviewResponse overview = parseAirQuality(json, stationId);
        cached = overview;
        cachedAtMs = now;
        return overview;
    }

    AirQualityOverviewResponse parseAirQuality(String json, String station) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode stationData = root.path("data").path(station);
            if (stationData.isMissingNode() || !stationData.fieldNames().hasNext()) {
                throw new IllegalStateException("UBA response missing data for station " + station);
            }

            Map.Entry<String, JsonNode> latest = latestEntry(stationData);
            JsonNode row = latest.getValue();

            return AirQualityOverviewResponse.builder()
                    .stationId(station)
                    .dateTime(LocalDateTime.parse(latest.getKey(), UBA_TIME))
                    .overallIndex(row.path(1).asInt(-1))
                    .incomplete(row.path(2).asInt(0) == 1)
                    .components(buildComponents(row))
                    .build();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse UBA response.", ex);
        }
    }

    /** Wählt den zeitlich jüngsten Messeintrag (Schlüssel sind sortierbare Zeitstempel). */
    private Map.Entry<String, JsonNode> latestEntry(JsonNode stationData) {
        Map.Entry<String, JsonNode> latest = null;
        Iterator<Map.Entry<String, JsonNode>> it = stationData.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (latest == null || entry.getKey().compareTo(latest.getKey()) > 0) {
                latest = entry;
            }
        }
        return latest;
    }

    /** Ab Index 3 stehen die Schadstoff-Arrays [compId, value, index, y-value]. */
    private List<AirQualityComponent> buildComponents(JsonNode row) {
        List<AirQualityComponent> components = new ArrayList<>();
        for (int i = 3; i < row.size(); i++) {
            JsonNode c = row.path(i);
            if (!c.isArray() || c.size() < 3) {
                continue;
            }
            int compId = c.path(0).asInt();
            ComponentMeta meta = COMPONENTS.get(compId);
            if (meta == null) {
                continue;
            }
            components.add(AirQualityComponent.builder()
                    .code(meta.code())
                    .symbol(meta.symbol())
                    .name(meta.name())
                    .value(decimalOrNull(c.path(1)))
                    .unit(meta.unit())
                    .index(c.path(2).asInt(-1))
                    .build());
        }
        return components;
    }

    private BigDecimal decimalOrNull(JsonNode node) {
        return (node == null || node.isMissingNode() || node.isNull())
                ? null : node.decimalValue();
    }

    private record ComponentMeta(String code, String symbol, String name, String unit) {
    }
}
