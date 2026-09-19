package com.household.manager.charging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.charging.dto.EnbwAddressDto;
import com.household.manager.charging.dto.EnbwChargePointDto;
import com.household.manager.charging.dto.EnbwStationDetailsDto;
import com.household.manager.charging.dto.EnbwStationDto;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Inoffizielles EnBW-mobility+-Backend. Alle EnBW-Spezifika (URLs, Header, Feldnamen,
 * Statustexte) leben ausschliesslich hier. Kein Login; nur der oeffentlich in der Web-App
 * eingebettete API-Key.
 */
@Component
public class EnbwChargingClient implements ChargingStationSource {

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
        String query = String.format(Locale.ROOT,
                "/chargestations?fromLat=%.6f&toLat=%.6f&fromLon=%.6f&toLon=%.6f&grouping=false",
                box.fromLat(), box.toLat(), box.fromLon(), box.toLon());
        return parseStations(mapper, get(query));
    }

    @Override
    public ChargingStationDetails stationDetails(String stationId) {
        // Formularkodierung macht ein Leerzeichen zu "+" - unschaedlich, EnBW-Ids (DE*XXX*E...) haben keine.
        return parseDetails(mapper, get("/chargestations/" + URLEncoder.encode(stationId, StandardCharsets.UTF_8)));
    }

    static List<ChargingStation> parseStations(ObjectMapper mapper, String json) {
        List<EnbwStationDto> dtos;
        try {
            dtos = mapper.readValue(json, new TypeReference<List<EnbwStationDto>>() { });
        } catch (IOException ex) {
            throw new ChargingSourceException("EnBW-Umkreisantwort nicht lesbar: " + ex.getMessage(), ex);
        }
        List<ChargingStation> stations = new ArrayList<>();
        for (EnbwStationDto dto : dtos) {
            if (dto.stationId() == null || dto.lat() == null || dto.lon() == null) {
                continue;
            }
            int total = dto.numberOfChargePoints() == null ? 0 : dto.numberOfChargePoints();
            int free = dto.availableChargePoints() == null ? 0 : Math.min(dto.availableChargePoints(), total);
            stations.add(new ChargingStation(dto.stationId(), displayName(dto), dto.operator(),
                    address(dto.address()), dto.lat(), dto.lon(), dto.maxPowerInKw(), total, free));
        }
        return stations;
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
                points.add(new ChargePoint(cp.evseId(), mapStatus(cp.status()), power, connector));
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

    private static String displayName(EnbwStationDto dto) {
        if (dto.stationName() != null && !dto.stationName().isBlank()) {
            return dto.stationName().trim();
        }
        String street = dto.address() == null ? null : dto.address().street();
        return Stream.of(dto.operator(), street)
                .filter(s -> s != null && !s.isBlank()).reduce((a, b) -> a + " " + b)
                .orElse(dto.stationId());
    }

    private static String address(EnbwAddressDto address) {
        if (address == null) {
            return null;
        }
        String cityLine = Stream.of(address.postalCode(), address.city())
                .filter(s -> s != null && !s.isBlank()).reduce((a, b) -> a + " " + b).orElse(null);
        return Stream.of(address.street(), cityLine)
                .filter(s -> s != null && !s.isBlank()).reduce((a, b) -> a + ", " + b).orElse(null);
    }

    private String get(String pathAndQuery) {
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
