package com.household.manager.ankersolix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.ankersolix.dto.AnkerSolixAutoControlStatusDto;
import com.household.manager.ankersolix.dto.AnkerSolixDeviceParamDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Automatically adjusts the Anker Solix solarbank output power to keep
 * grid consumption (Netzbezug) as close to zero as possible.
 *
 * <p>The regulation uses a feedback loop: each cycle reads the current grid
 * power from the Tasmota smart meter and adjusts the battery output
 * accordingly. If grid power is negative (feeding into the public grid),
 * the battery output is reduced to avoid wasting energy.
 *
 * <p>Enabled only when {@code ankersolix.auto-control.enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "ankersolix.auto-control.enabled", havingValue = "true")
@Slf4j
public class AnkerSolixAutoControlService {

    private final AnkerSolixService ankerSolixService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String tasmotaUrl;
    private final int thresholdW;

    private volatile int lastSetOutputW = -1;
    private volatile LocalDateTime lastAdjustmentTime;
    private volatile double lastGridPowerW;
    private volatile String lastSkipReason;

    public AnkerSolixAutoControlService(
            AnkerSolixService ankerSolixService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${tasmota.electricity.url}") String tasmotaUrl,
            @Value("${ankersolix.auto-control.threshold-w:10}") int thresholdW) {
        this.ankerSolixService = ankerSolixService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.tasmotaUrl = tasmotaUrl;
        this.thresholdW = thresholdW;
    }

    /**
     * Periodically reads the current grid power from the Tasmota smart meter
     * and adjusts the solarbank output to keep grid consumption near zero.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Poll Tasmota for current grid power (positive = importing, negative = exporting)</li>
     *   <li>Read current battery output (cached from last adjustment, or from API on first run)</li>
     *   <li>Calculate new target: {@code newOutput = currentOutput + gridPower}</li>
     *   <li>Clamp to device min/max limits</li>
     *   <li>Only apply if change exceeds the configured threshold</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${ankersolix.auto-control.interval-ms:30000}")
    public void autoAdjustOutputPower() {
        try {
            double gridPowerW = pollTasmotaGridPower();
            lastGridPowerW = gridPowerW;

            AnkerSolixDeviceParamDto deviceParams = ankerSolixService.getDeviceParams();
            int minLoad = deviceParams.getMinLoadW();
            int maxLoad = deviceParams.getMaxLoadW();
            int currentOutputW = lastSetOutputW >= 0 ? lastSetOutputW : deviceParams.getCurrentOutputW();

            int targetOutputW = currentOutputW + (int) Math.round(gridPowerW);
            int clampedOutputW = Math.max(minLoad, Math.min(maxLoad, targetOutputW));

            int delta = Math.abs(clampedOutputW - currentOutputW);
            if (delta < thresholdW) {
                lastSkipReason = String.format(
                        "delta=%dW < threshold=%dW (grid=%.0fW, current=%dW)",
                        delta, thresholdW, gridPowerW, currentOutputW);
                log.debug("Auto-control: skipped – {}", lastSkipReason);
                return;
            }

            log.info("Auto-control: grid={}W  current={}W  target={}W  clamped={}W  (min={}, max={})",
                    (int) gridPowerW, currentOutputW, targetOutputW, clampedOutputW, minLoad, maxLoad);

            ankerSolixService.setOutputPower(clampedOutputW);
            lastSetOutputW = clampedOutputW;
            lastAdjustmentTime = LocalDateTime.now();
            lastSkipReason = null;

        } catch (Exception ex) {
            log.warn("Auto-control cycle failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Returns the current status of the auto-control regulation.
     */
    public AnkerSolixAutoControlStatusDto getStatus() {
        return AnkerSolixAutoControlStatusDto.builder()
                .enabled(true)
                .thresholdW(thresholdW)
                .lastSetOutputW(lastSetOutputW >= 0 ? lastSetOutputW : null)
                .lastGridPowerW(lastAdjustmentTime != null || lastSkipReason != null ? lastGridPowerW : null)
                .lastAdjustmentTime(lastAdjustmentTime)
                .lastSkipReason(lastSkipReason)
                .build();
    }

    /**
     * Polls the Tasmota smart meter directly via HTTP and returns the
     * instantaneous grid power in watts.
     * Positive = importing from grid, negative = exporting to grid.
     */
    private double pollTasmotaGridPower() {
        String requestUrl = normalizeTasmotaUrl(tasmotaUrl);
        String json = restTemplate.getForObject(requestUrl, String.class);
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Empty response from Tasmota smart meter");
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode status = root.path("StatusSNS");
            if (status.isMissingNode()) {
                throw new IllegalStateException("Missing StatusSNS in Tasmota response");
            }

            // Find the first object node under StatusSNS (the telemetry data)
            JsonNode telemetryNode = null;
            var fields = status.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (!"Time".equals(entry.getKey()) && entry.getValue().isObject()) {
                    telemetryNode = entry.getValue();
                    break;
                }
            }
            if (telemetryNode == null) {
                throw new IllegalStateException("No telemetry data found in Tasmota response");
            }

            JsonNode powerNode = telemetryNode.path("momentanwirkleistung");
            if (powerNode.isMissingNode()) {
                throw new IllegalStateException("Missing momentanwirkleistung in Tasmota telemetry");
            }

            return powerNode.decimalValue().doubleValue();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Tasmota response", ex);
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
            log.warn("Failed to normalize Tasmota URL, using raw: {}", rawUrl);
            return rawUrl;
        }
    }
}
