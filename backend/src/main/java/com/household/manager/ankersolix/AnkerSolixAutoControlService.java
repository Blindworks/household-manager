package com.household.manager.ankersolix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.ankersolix.dto.AnkerSolixAutoControlSettingsDto;
import com.household.manager.ankersolix.dto.AnkerSolixAutoControlStatusDto;
import com.household.manager.ankersolix.dto.AnkerSolixDeviceParamDto;
import com.household.manager.ankersolix.dto.AnkerSolixLiveDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

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
 * <p>Always instantiated when the Anker Solix integration is enabled.
 * The auto-control regulation itself can be toggled at runtime via
 * {@link #updateSettings(AnkerSolixAutoControlSettingsDto)}.
 */
@Service
@ConditionalOnProperty(name = "ankersolix.enabled", havingValue = "true")
@Slf4j
public class AnkerSolixAutoControlService {

    private static final long BASE_POLL_INTERVAL_MS = 5000;

    private final AnkerSolixService ankerSolixService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String tasmotaUrl;

    private volatile boolean enabled;
    private volatile int thresholdW;
    private volatile long intervalMs;

    private volatile boolean forceDischargeEnabled;
    private volatile int forceDischargeMinBatteryPercent;
    private volatile boolean forceDischargeActive = false;
    private volatile int lastBatteryPercent = -1;

    private volatile int lastSetOutputW = -1;
    private volatile LocalDateTime lastAdjustmentTime;
    private volatile double lastGridPowerW;
    private volatile String lastSkipReason;
    private volatile long lastRunTimestamp = 0;

    public AnkerSolixAutoControlService(
            AnkerSolixService ankerSolixService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${tasmota.electricity.url}") String tasmotaUrl,
            @Value("${ankersolix.auto-control.enabled:false}") boolean enabled,
            @Value("${ankersolix.auto-control.threshold-w:10}") int thresholdW,
            @Value("${ankersolix.auto-control.interval-ms:30000}") long intervalMs,
            @Value("${ankersolix.auto-control.force-discharge.enabled:false}") boolean forceDischargeEnabled,
            @Value("${ankersolix.auto-control.force-discharge.min-battery-percent:10}") int forceDischargeMinBatteryPercent) {
        this.ankerSolixService = ankerSolixService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.tasmotaUrl = tasmotaUrl;
        this.enabled = enabled;
        this.thresholdW = thresholdW;
        this.intervalMs = intervalMs;
        this.forceDischargeEnabled = forceDischargeEnabled;
        this.forceDischargeMinBatteryPercent = forceDischargeMinBatteryPercent;
    }

    /**
     * Base-rate scheduled method that checks whether the configurable interval
     * has elapsed before executing the actual regulation logic. This allows
     * the interval to be changed at runtime without restarting the scheduler.
     */
    @Scheduled(fixedDelay = BASE_POLL_INTERVAL_MS)
    public void scheduledTick() {
        if (!enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastRunTimestamp < intervalMs) {
            return;
        }
        lastRunTimestamp = now;

        autoAdjustOutputPower();
    }

    /**
     * Reads the current grid power from the Tasmota smart meter
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
    private void autoAdjustOutputPower() {
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

        evaluateForceDischarge();
    }

    /**
     * Evaluates whether force-discharge (Entladung nur durch Batterie) should be
     * enabled or disabled based on current grid power and battery SOC.
     *
     * <p>Logic:
     * <ul>
     *   <li>Enable when: grid import &gt; 0 AND battery &gt; threshold AND not yet active</li>
     *   <li>Disable when: battery &lt;= threshold AND currently active</li>
     *   <li>Once enabled, stays active until battery drops below threshold (hysteresis)</li>
     * </ul>
     *
     * <p>Runs in its own try-catch to not affect the output power regulation.
     */
    private void evaluateForceDischarge() {
        if (!forceDischargeEnabled) {
            return;
        }

        try {
            AnkerSolixLiveDto liveData = ankerSolixService.getLiveData();
            int batteryPercent = liveData.getBatteryPercent();
            lastBatteryPercent = batteryPercent;

            if (batteryPercent <= forceDischargeMinBatteryPercent && forceDischargeActive) {
                log.info("Force-discharge: disabling – battery {}% <= threshold {}%",
                        batteryPercent, forceDischargeMinBatteryPercent);
                ankerSolixService.setForceDischarge(false);
                forceDischargeActive = false;
            } else if (lastGridPowerW > 0 && batteryPercent > forceDischargeMinBatteryPercent && !forceDischargeActive) {
                log.info("Force-discharge: enabling – grid={}W, battery={}% > threshold {}%",
                        (int) lastGridPowerW, batteryPercent, forceDischargeMinBatteryPercent);
                ankerSolixService.setForceDischarge(true);
                forceDischargeActive = true;
            }
        } catch (Exception ex) {
            log.warn("Force-discharge evaluation failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Returns the current status of the auto-control regulation.
     */
    public AnkerSolixAutoControlStatusDto getStatus() {
        return AnkerSolixAutoControlStatusDto.builder()
                .enabled(enabled)
                .thresholdW(thresholdW)
                .intervalMs(intervalMs)
                .lastSetOutputW(lastSetOutputW >= 0 ? lastSetOutputW : null)
                .lastGridPowerW(lastAdjustmentTime != null || lastSkipReason != null ? lastGridPowerW : null)
                .lastAdjustmentTime(lastAdjustmentTime)
                .lastSkipReason(lastSkipReason)
                .forceDischargeActive(forceDischargeEnabled ? forceDischargeActive : null)
                .lastBatteryPercent(lastBatteryPercent >= 0 ? lastBatteryPercent : null)
                .build();
    }

    /**
     * Returns the current settings of the auto-control regulation.
     */
    public AnkerSolixAutoControlSettingsDto getSettings() {
        return AnkerSolixAutoControlSettingsDto.builder()
                .enabled(enabled)
                .thresholdW(thresholdW)
                .intervalMs(intervalMs)
                .forceDischargeEnabled(forceDischargeEnabled)
                .forceDischargeMinBatteryPercent(forceDischargeMinBatteryPercent)
                .build();
    }

    /**
     * Updates the auto-control settings at runtime.
     */
    public void updateSettings(AnkerSolixAutoControlSettingsDto settings) {
        boolean wasForceDischargeEnabled = this.forceDischargeEnabled;

        this.enabled = settings.isEnabled();
        this.thresholdW = settings.getThresholdW();
        this.intervalMs = settings.getIntervalMs();
        this.forceDischargeEnabled = settings.isForceDischargeEnabled();
        this.forceDischargeMinBatteryPercent = settings.getForceDischargeMinBatteryPercent();

        // Deactivate force-discharge on the device if the feature was just disabled
        if (wasForceDischargeEnabled && !forceDischargeEnabled && forceDischargeActive) {
            try {
                ankerSolixService.setForceDischarge(false);
                forceDischargeActive = false;
                log.info("Force-discharge deactivated on device (feature disabled via settings)");
            } catch (Exception ex) {
                log.warn("Failed to deactivate force-discharge on device: {}", ex.getMessage(), ex);
            }
        }

        log.info("Auto-control settings updated: enabled={}, thresholdW={}, intervalMs={}, " +
                        "forceDischarge={}, minBattery={}%",
                enabled, thresholdW, intervalMs, forceDischargeEnabled, forceDischargeMinBatteryPercent);
    }

    /**
     * Manually enables or disables force-discharge.
     * Can be used independently of the auto-control loop.
     */
    public void setForceDischargeManual(boolean enabled) {
        ankerSolixService.setForceDischarge(enabled);
        forceDischargeActive = enabled;
        log.info("Force-discharge manually set to {}", enabled);
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
