package com.household.manager.charging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.charging.dto.EnbwChargePointDto;
import com.household.manager.charging.dto.EnbwStationDetailsDto;
import com.household.manager.charging.dto.EnbwStationDto;
import com.household.manager.charging.dto.EnbwViewPortDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Inoffizielles EnBW-mobility+-Backend. Alle EnBW-Spezifika (URLs, Header, Feldnamen,
 * Statustexte) leben ausschliesslich hier. Kein Login; nur der oeffentlich in der Web-App
 * eingebettete API-Key.
 *
 * <p>Die echte Umkreis-Antwort gruppiert grosse Boxen serverseitig zu Sammelzeilen
 * ({@code grouped: true}, {@code stationId: null}, nur ein {@code viewPort}) - unabhaengig
 * vom gesendeten {@code grouping=false}. {@link #searchArea} loest solche Sammelzeilen
 * rekursiv per Drill-down auf, bis nur noch Einzelstationen oder das Anfrage-/Tiefenlimit
 * uebrig sind.
 */
@Slf4j
@Component
public class EnbwChargingClient implements ChargingStationSource {

    /** Harte Obergrenze an Anfragen je {@link #searchArea}-Aufruf (Schutz vor Anfrageflut). */
    static final int MAX_AREA_REQUESTS = 150;
    /** Rekursionstiefe des Drill-downs, unabhaengig vom Anfragelimit. */
    static final int MAX_DRILL_DEPTH = 6;
    /** Boxen, die in beiden Dimensionen kleiner sind, lassen sich nicht sinnvoll weiter teilen. */
    private static final double MIN_SPLITTABLE_DEGREES = 1e-4;

    private static final Pattern OPERATOR_CODE_SUFFIX = Pattern.compile("\\s*-\\s*\\(([A-Z0-9*]+)\\)\\s*$");

    private final ChargingProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public EnbwChargingClient(ChargingProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.httpClient = createHttpClient(properties.getHttpTimeoutMs());
    }

    /** HTTP/1.1 erzwungen (Muster BlinkSidecarClient); eigene Methode fuer den Regressionstest. */
    static HttpClient createHttpClient(int timeoutMs) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public List<ChargingStation> searchArea(BoundingBox box, double minPowerKw) {
        Map<String, ChargingStation> collected = new LinkedHashMap<>();
        int[] requestCount = {0};
        boolean capHit = drillArea(box, minPowerKw, 0, collected, requestCount);
        if (capHit) {
            log.warn("Ladesaeulen-Umkreis: Anfragelimit erreicht, Liste unvollstaendig");
        }
        return new ArrayList<>(collected.values());
    }

    /**
     * Fragt die Box ab, sammelt ihre Einzelstationen und loest ihre Sammelzeilen rekursiv auf.
     * Gibt {@code true} zurueck, sobald das Anfragelimit erreicht wurde (Ergebnis unvollstaendig).
     */
    private boolean drillArea(BoundingBox box, double minPowerKw, int depth,
                               Map<String, ChargingStation> collected, int[] requestCount) {
        if (requestCount[0] >= MAX_AREA_REQUESTS) {
            return true;
        }
        requestCount[0]++;
        AreaPage page = parseArea(mapper, fetch(areaQuery(box, minPowerKw)));
        for (ChargingStation station : page.stations()) {
            collected.putIfAbsent(station.stationId(), station);
        }
        if (depth >= MAX_DRILL_DEPTH) {
            return false;
        }
        for (BoundingBox group : page.groups()) {
            if (isDegenerate(group)) {
                log.debug("Ladesaeulen-Umkreis: Gruppen-Box zu klein zum Aufteilen, uebersprungen ({})", group);
                continue;
            }
            if (requestCount[0] >= MAX_AREA_REQUESTS) {
                return true;
            }
            if (drillArea(group, minPowerKw, depth + 1, collected, requestCount)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDegenerate(BoundingBox box) {
        return (box.toLat() - box.fromLat()) < MIN_SPLITTABLE_DEGREES
                && (box.toLon() - box.fromLon()) < MIN_SPLITTABLE_DEGREES;
    }

    private static String areaQuery(BoundingBox box, double minPowerKw) {
        StringBuilder query = new StringBuilder(String.format(Locale.ROOT,
                "/chargestations?fromLat=%.6f&toLat=%.6f&fromLon=%.6f&toLon=%.6f&grouping=false&groupingDivisor=15",
                box.fromLat(), box.toLat(), box.fromLon(), box.toLon()));
        if (minPowerKw > 0) {
            query.append("&minPower=").append((int) minPowerKw);
        }
        return query.toString();
    }

    @Override
    public ChargingStationDetails stationDetails(String stationId) {
        // Formularkodierung macht ein Leerzeichen zu "+" - unschaedlich, EnBW-Ids (DE*XXX*E...) haben keine.
        return parseDetails(mapper, fetch("/chargestations/" + URLEncoder.encode(stationId, StandardCharsets.UTF_8)));
    }

    /** Eine geparste Umkreis-Antwortseite: aufgeloeste Einzelstationen plus offene Sammel-Boxen. */
    record AreaPage(List<ChargingStation> stations, List<BoundingBox> groups) {
    }

    static AreaPage parseArea(ObjectMapper mapper, String json) {
        List<EnbwStationDto> dtos;
        try {
            dtos = mapper.readValue(json, new TypeReference<List<EnbwStationDto>>() { });
        } catch (IOException ex) {
            throw new ChargingSourceException("EnBW-Umkreisantwort nicht lesbar: " + ex.getMessage(), ex);
        }
        List<ChargingStation> stations = new ArrayList<>();
        List<BoundingBox> groups = new ArrayList<>();
        for (EnbwStationDto dto : dtos) {
            if (Boolean.TRUE.equals(dto.grouped())) {
                BoundingBox box = toBoundingBox(dto.viewPort());
                if (box != null) {
                    groups.add(box);
                }
                continue;
            }
            if (dto.stationId() == null || dto.lat() == null || dto.lon() == null) {
                continue;
            }
            int total = dto.numberOfChargePoints() == null ? 0 : dto.numberOfChargePoints();
            int free = dto.availableChargePoints() == null ? 0 : Math.min(dto.availableChargePoints(), total);
            stations.add(new ChargingStation(dto.stationId(), displayName(dto), operatorName(dto.operator()),
                    dto.shortAddress(), dto.lat(), dto.lon(), dto.maxPowerInKw(), total, free));
        }
        return new AreaPage(stations, groups);
    }

    /** Nur fuer die alten Inline-JSON-Tests: eine Umkreis-Antwort ohne Interesse an Gruppen. */
    static List<ChargingStation> parseStations(ObjectMapper mapper, String json) {
        return parseArea(mapper, json).stations();
    }

    private static BoundingBox toBoundingBox(EnbwViewPortDto viewPort) {
        if (viewPort == null || viewPort.lowerLeftLat() == null || viewPort.lowerLeftLon() == null
                || viewPort.upperRightLat() == null || viewPort.upperRightLon() == null) {
            return null;
        }
        return new BoundingBox(viewPort.lowerLeftLat(), viewPort.upperRightLat(),
                viewPort.lowerLeftLon(), viewPort.upperRightLon());
    }

    static ChargingStationDetails parseDetails(ObjectMapper mapper, String json) {
        EnbwStationDetailsDto dto;
        try {
            dto = mapper.readValue(json, EnbwStationDetailsDto.class);
        } catch (IOException ex) {
            throw new ChargingSourceException("EnBW-Detailantwort nicht lesbar: " + ex.getMessage(), ex);
        }
        List<ChargePoint> points = new ArrayList<>();
        if (dto.chargePoints() != null) {
            for (EnbwChargePointDto cp : dto.chargePoints()) {
                if (cp.evseId() == null) {
                    continue;
                }
                Double power = cp.connectors() == null ? null : cp.connectors().stream()
                        .map(c -> c.maxPowerInKw()).filter(p -> p != null).max(Double::compare).orElse(null);
                String connector = cp.connectors() == null || cp.connectors().isEmpty()
                        ? null : cp.connectors().get(0).plugTypeName();
                Instant statusSince = cp.state() == null || cp.state().updatedAt() == null
                        ? null : Instant.ofEpochMilli(cp.state().updatedAt());
                points.add(new ChargePoint(cp.evseId(), mapStatus(cp.status()), power, connector, statusSince));
            }
        }
        return new ChargingStationDetails(dto.stationId(), points);
    }

    /** Fail-safe: nur bekannte Texte werden gedeutet, alles andere ist UNKNOWN - nie FREE. */
    static ChargePointStatus mapStatus(String raw) {
        if (raw == null) {
            return ChargePointStatus.UNKNOWN;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "AVAILABLE" -> ChargePointStatus.FREE;
            case "OCCUPIED", "CHARGING" -> ChargePointStatus.OCCUPIED;
            case "OUT_OF_SERVICE", "OUTOFSERVICE", "OFFLINE" -> ChargePointStatus.OUT_OF_SERVICE;
            default -> ChargePointStatus.UNKNOWN;
        };
    }

    /** Name: erstes Segment von {@code shortAddress} vor dem ersten Komma, sonst Betreiber, sonst die Id. */
    private static String displayName(EnbwStationDto dto) {
        String fromAddress = firstAddressSegment(dto.shortAddress());
        if (fromAddress != null) {
            return fromAddress;
        }
        String operator = operatorName(dto.operator());
        if (operator != null) {
            return operator;
        }
        return dto.stationId();
    }

    private static String firstAddressSegment(String shortAddress) {
        if (shortAddress == null || shortAddress.isBlank()) {
            return null;
        }
        int comma = shortAddress.indexOf(',');
        String segment = (comma >= 0 ? shortAddress.substring(0, comma) : shortAddress).trim();
        return segment.isBlank() ? null : segment;
    }

    /** "*" (Gruppen-Platzhalter) und der " - (CODE)"-Anhang gelten nicht als Betreibername. */
    private static String operatorName(String operator) {
        if (operator == null) {
            return null;
        }
        String trimmed = operator.trim();
        if (trimmed.isEmpty() || trimmed.equals("*")) {
            return null;
        }
        String stripped = OPERATOR_CODE_SUFFIX.matcher(trimmed).replaceFirst("").trim();
        return stripped.isBlank() ? null : stripped;
    }

    /** Package-sichtbar fuer Tests: kapselt den eigentlichen HTTP-Abruf. */
    String fetch(String pathAndQuery) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEnbw().getBaseUrl() + pathAndQuery))
                .timeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .header("Ocp-Apim-Subscription-Key", properties.getEnbw().getApiKey())
                .header("Origin", properties.getEnbw().getOrigin())
                .header("Referer", properties.getEnbw().getOrigin() + "/")
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw new ChargingRateLimitException("EnBW hat das Rate-Limit gemeldet (429).");
            }
            if (response.statusCode() / 100 != 2) {
                throw new ChargingSourceException("EnBW antwortet HTTP " + response.statusCode()
                        + " fuer " + pathAndQuery);
            }
            return response.body();
        } catch (IOException ex) {
            throw new ChargingSourceException("EnBW nicht erreichbar: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ChargingSourceException("EnBW-Abruf unterbrochen", ex);
        }
    }
}
