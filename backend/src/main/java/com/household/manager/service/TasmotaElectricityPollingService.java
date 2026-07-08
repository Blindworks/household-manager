package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.dto.TasmotaElectricityPollingStatusResponse;
import com.household.manager.dto.TasmotaStatusResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.TasmotaElectricityReading;
import com.household.manager.repository.TasmotaElectricityReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Polls Tasmota electricity meter data at a fixed interval.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TasmotaElectricityPollingService {

    private static final String WEEKLY_SCHEDULE = "Jeden Freitag um 09:00 (Europe/Berlin)";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TasmotaElectricityReadingRepository repository;
    private final TaskScheduler taskScheduler;
    private final EntityStateService entityStateService;

    @Value("${tasmota.electricity.url}")
    private String tasmotaUrl;

    private volatile LocalDateTime lastPollTime;
    private volatile String lastError;

    public TasmotaElectricityPollingStatusResponse getStatus() {
        return TasmotaElectricityPollingStatusResponse.builder()
                .url(tasmotaUrl)
                .schedule(WEEKLY_SCHEDULE)
                .lastPollTime(lastPollTime)
                .lastError(lastError)
                .build();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::safePoll, Instant.now());
    }

    /**
     * Weekly snapshot every Friday at 09:00 (Europe/Berlin).
     */
    @Scheduled(cron = "0 0 9 ? * FRI", zone = "Europe/Berlin")
    public void weeklySnapshot() {
        safePoll();
    }

    private void safePoll() {
        try {
            lastPollTime = LocalDateTime.now();
            String requestUrl = normalizeTasmotaUrl(tasmotaUrl);
            String json = restTemplate.getForObject(requestUrl, String.class);
            if (json == null || json.isBlank()) {
                log.warn("Tasmota response was empty");
                lastError = "Empty response";
                return;
            }

            TasmotaStatusResponse response = objectMapper.readValue(json, TasmotaStatusResponse.class);
            if (response.getStatusSNS() == null) {
                log.warn("Tasmota response missing StatusSNS. Raw: {}", json);
                if (!tryParseWithJsonTree(json)) {
                    log.warn("Fallback parsing failed");
                    lastError = "StatusSNS missing and fallback failed";
                }
                return;
            }

            LocalDateTime readingTime = LocalDateTime.parse(response.getStatusSNS().getTime());
            Optional<TasmotaStatusResponse.Telemetry> telemetry = response.getStatusSNS()
                    .getTelemetry()
                    .values()
                    .stream()
                    .findFirst();

            if (telemetry.isEmpty()) {
                log.warn("No telemetry payload found in Tasmota response");
                lastError = "No telemetry payload found";
                return;
            }

            TasmotaStatusResponse.Telemetry payload = telemetry.get();
            if (payload.getPosWirkenergieTariflos() == null || payload.getMomentaneWirkleistung() == null) {
                log.warn("Telemetry missing required fields");
                lastError = "Telemetry missing required fields";
                return;
            }

            reportEntityStates(payload.getPosWirkenergieTariflos(), payload.getMomentaneWirkleistung());

            if (repository.existsByReadingTime(readingTime)) {
                log.debug("Tasmota reading for {} already exists, skipping", readingTime);
                return;
            }

            TasmotaElectricityReading reading = TasmotaElectricityReading.builder()
                    .readingTime(readingTime)
                    .posWirkenergieTariflos(payload.getPosWirkenergieTariflos())
                    .momentaneWirkleistung(payload.getMomentaneWirkleistung())
                    .build();

            repository.save(reading);
            log.info("Saved Tasmota electricity reading at {}", readingTime);
            lastError = null;
        } catch (Exception ex) {
            log.error("Failed to poll Tasmota electricity meter", ex);
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    private boolean tryParseWithJsonTree(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode status = root.get("StatusSNS");
            if (status == null || status.isNull()) {
                return false;
            }

            String timeText = status.path("Time").asText(null);
            if (timeText == null || timeText.isBlank()) {
                return false;
            }
            LocalDateTime readingTime = LocalDateTime.parse(timeText);

            JsonNode telemetryNode = null;
            var fields = status.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if ("Time".equals(entry.getKey())) {
                    continue;
                }
                if (entry.getValue().isObject()) {
                    telemetryNode = entry.getValue();
                    break;
                }
            }
            if (telemetryNode == null) {
                return false;
            }

            JsonNode posNode = telemetryNode.get("pos_wirk_tariflos");
            JsonNode powerNode = telemetryNode.get("momentanwirkleistung");
            if (posNode == null || powerNode == null) {
                return false;
            }

            reportEntityStates(posNode.decimalValue(), powerNode.decimalValue());

            if (repository.existsByReadingTime(readingTime)) {
                log.debug("Tasmota reading for {} already exists, skipping", readingTime);
                return true;
            }

            TasmotaElectricityReading reading = TasmotaElectricityReading.builder()
                    .readingTime(readingTime)
                    .posWirkenergieTariflos(posNode.decimalValue())
                    .momentaneWirkleistung(powerNode.decimalValue())
                    .build();

            repository.save(reading);
            log.info("Saved Tasmota electricity reading at {}", readingTime);
            lastError = null;
            return true;
        } catch (Exception ex) {
            log.error("Fallback parsing failed", ex);
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return false;
        }
    }

    private void reportEntityStates(BigDecimal energyKwh, BigDecimal powerW) {
        try {
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TASMOTA, "main", "energy"))
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.TASMOTA)
                    .sourceRef("main")
                    .friendlyName("Stromzähler Wirkenergie")
                    .state(energyKwh.toPlainString())
                    .attributes(Map.of("unit", "kWh", "deviceClass", "energy"))
                    .build());
            entityStateService.reportState(EntityStateUpdate.builder()
                    .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TASMOTA, "main", "power"))
                    .domain(EntityDomain.SENSOR)
                    .source(EntitySource.TASMOTA)
                    .sourceRef("main")
                    .friendlyName("Stromzähler Momentanleistung")
                    .state(powerW.toPlainString())
                    .attributes(Map.of("unit", "W", "deviceClass", "power"))
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to report tasmota entity states: {}", ex.getMessage());
        }
    }

    private String normalizeTasmotaUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String baseUrl = UriComponentsBuilder.newInstance()
                    .scheme(uri.getScheme())
                    .host(uri.getHost())
                    .port(uri.getPort())
                    .path(uri.getPath())
                    .build()
                    .toUriString();

            String query = uri.getRawQuery();
            if (query == null || !query.contains("cmnd=")) {
                return rawUrl;
            }

            String cmndEncoded = query.substring(query.indexOf("cmnd=") + "cmnd=".length());
            String cmndDecoded = URLDecoder.decode(cmndEncoded, StandardCharsets.UTF_8);
            return UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("cmnd", cmndDecoded)
                    .build()
                    .toUriString();
        } catch (Exception ex) {
            log.warn("Failed to normalize Tasmota URL, using raw URL: {}", rawUrl);
            return rawUrl;
        }
    }
}
