package com.household.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.TasmotaElectricityLiveResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Streams live Tasmota electricity data via SSE.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TasmotaElectricityLiveService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;

    @Value("${tasmota.electricity.url}")
    private String tasmotaUrl;

    @Value("${tasmota.electricity.live.interval-ms:1000}")
    private long intervalMs;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> scheduledFuture;

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError(e -> removeEmitter(emitter));

        ensureSchedulerRunning();
        return emitter;
    }

    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            stopScheduler();
        }
    }

    private void ensureSchedulerRunning() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                return;
            }
            long delay = Math.max(500, intervalMs);
            scheduledFuture = taskScheduler.scheduleWithFixedDelay(this::pollAndBroadcast, Duration.ofMillis(delay));
            log.info("Live Tasmota electricity polling started ({} ms)", delay);
        }
    }

    private void stopScheduler() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
                log.info("Live Tasmota electricity polling stopped");
            }
        }
    }

    private void pollAndBroadcast() {
        if (emitters.isEmpty()) {
            stopScheduler();
            return;
        }

        try {
            String requestUrl = normalizeTasmotaUrl(tasmotaUrl);
            String json = restTemplate.getForObject(requestUrl, String.class);
            if (json == null || json.isBlank()) {
                return;
            }

            TasmotaElectricityLiveResponse response = parseLive(json);
            if (response == null) {
                return;
            }

            emitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event().name("live").data(response));
                } catch (Exception ex) {
                    removeEmitter(emitter);
                }
            });
        } catch (Exception ex) {
            log.debug("Live polling failed: {}", ex.getMessage());
        }
    }

    private TasmotaElectricityLiveResponse parseLive(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode status = root.get("StatusSNS");
        if (status == null || status.isNull()) {
            return null;
        }

        String timeText = status.path("Time").asText(null);
        if (timeText == null || timeText.isBlank()) {
            return null;
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
            return null;
        }

        JsonNode powerNode = telemetryNode.get("momentanwirkleistung");
        JsonNode posNode = telemetryNode.get("pos_wirk_tariflos");
        if (powerNode == null) {
            return null;
        }

        BigDecimal power = powerNode.decimalValue();
        BigDecimal posEnergy = posNode != null ? posNode.decimalValue() : null;

        return TasmotaElectricityLiveResponse.builder()
                .readingTime(readingTime)
                .momentaneWirkleistung(power)
                .posWirkenergieTariflos(posEnergy)
                .build();
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
