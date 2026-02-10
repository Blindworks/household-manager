package com.household.manager.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.dto.TasmotaElectricityPollingStatusResponse;
import com.household.manager.dto.TasmotaElectricityPollingUpdateRequest;
import com.household.manager.dto.TasmotaStatusResponse;
import com.household.manager.model.entity.TasmotaElectricityReading;
import com.household.manager.model.entity.TasmotaPollingSettings;
import com.household.manager.repository.TasmotaElectricityReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.scheduling.TaskScheduler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.Optional;

/**
 * Polls Tasmota electricity meter data at a fixed interval.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TasmotaElectricityPollingService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TasmotaElectricityReadingRepository repository;
    private final TaskScheduler taskScheduler;
    private final TasmotaPollingSettingsService settingsService;

    private String tasmotaUrl;
    private long intervalMs;
    private boolean enabled;

    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> scheduledFuture;
    private volatile LocalDateTime lastPollTime;
    private volatile LocalDateTime lastSuccessTime;
    private volatile String lastError;

    @jakarta.annotation.PostConstruct
    public void initializeScheduler() {
        loadSettingsFromDb();
        reschedule();
    }

    @jakarta.annotation.PreDestroy
    public void shutdownScheduler() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
            }
        }
    }

    public TasmotaElectricityPollingStatusResponse getStatus() {
        loadSettingsFromDb();
        return TasmotaElectricityPollingStatusResponse.builder()
                .enabled(enabled)
                .intervalMs(intervalMs)
                .url(tasmotaUrl)
                .lastPollTime(lastPollTime)
                .lastSuccessTime(lastSuccessTime)
                .lastError(lastError)
                .build();
    }

    public TasmotaElectricityPollingStatusResponse updateConfig(TasmotaElectricityPollingUpdateRequest request) {
        TasmotaPollingSettings settings = settingsService.updateElectricitySettings(request);
        applySettings(settings);
        reschedule();
        return getStatus();
    }

    public void triggerOnce() {
        taskScheduler.schedule(this::safePoll, Instant.now());
    }

    private void reschedule() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
            }
            if (!enabled) {
                log.info("Tasmota electricity polling disabled");
                return;
            }
            long delay = Math.max(1000, intervalMs);
            scheduledFuture = taskScheduler.scheduleWithFixedDelay(this::safePoll, Duration.ofMillis(delay));
            log.info("Tasmota electricity polling scheduled every {} ms", delay);
        }
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
            lastSuccessTime = LocalDateTime.now();
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
            lastSuccessTime = LocalDateTime.now();
            lastError = null;
            return true;
        } catch (Exception ex) {
            log.error("Fallback parsing failed", ex);
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return false;
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

    private void loadSettingsFromDb() {
        TasmotaPollingSettings settings = settingsService.getOrCreateElectricitySettings();
        applySettings(settings);
    }

    private void applySettings(TasmotaPollingSettings settings) {
        enabled = settings.isEnabled();
        intervalMs = settings.getIntervalMs();
        tasmotaUrl = settings.getUrl();
    }
}
