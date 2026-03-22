package com.household.manager.ankersolix;

import com.household.manager.ankersolix.dto.AnkerSolixDeviceParamDto;
import com.household.manager.ankersolix.dto.AnkerSolixLiveDto;
import com.household.manager.dto.TasmotaElectricityReadingResponse;
import com.household.manager.service.TasmotaElectricityReadingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Automatically adjusts the Anker Solix solarbank output power based on
 * current household consumption (from Tasmota) and PV production.
 *
 * <p>Enabled only when {@code ankersolix.auto-control.enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "ankersolix.auto-control.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AnkerSolixAutoControlService {

    private final AnkerSolixService ankerSolixService;
    private final TasmotaElectricityReadingService tasmotaElectricityReadingService;

    /**
     * Periodically reads current consumption and PV data, then adjusts the
     * solarbank output so that PV + battery cover the home load as closely as
     * possible within the device's min/max limits.
     */
    @Scheduled(fixedDelayString = "${ankersolix.auto-control.interval-ms:30000}")
    public void autoAdjustOutputPower() {
        try {
            double tasmotaPowerW = resolveTasmotaPowerW();
            AnkerSolixLiveDto liveData = ankerSolixService.getLiveData();
            AnkerSolixDeviceParamDto deviceParams = ankerSolixService.getDeviceParams();

            double pvPowerW   = liveData.getPvPowerW();
            int    batteryPct = liveData.getBatteryPercent();
            int    minLoad    = deviceParams.getMinLoadW();
            int    maxLoad    = deviceParams.getMaxLoadW();

            // Target: solarbank should cover what PV cannot deliver to the home
            int targetOutputW = (int) Math.round(tasmotaPowerW - pvPowerW);
            int clampedOutputW = clamp(targetOutputW, minLoad, maxLoad);

            log.debug(
                    "Auto-control: tasmota={}W  pv={}W  battery={}%  "
                    + "target={}W  clamped={}W  (min={}, max={})",
                    (int) tasmotaPowerW, (int) pvPowerW, batteryPct,
                    targetOutputW, clampedOutputW, minLoad, maxLoad);

            ankerSolixService.setOutputPower(clampedOutputW);

        } catch (Exception ex) {
            log.warn("Auto-control cycle failed: {}", ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the most recent Tasmota meter reading and returns its power value in
     * watts. Falls back to 0 W when no reading is available.
     */
    private double resolveTasmotaPowerW() {
        TasmotaElectricityReadingResponse latest = tasmotaElectricityReadingService.getLatestReading();
        if (latest == null || latest.getMomentaneWirkleistung() == null) {
            log.debug("No Tasmota reading available – assuming 0 W");
            return 0.0;
        }
        return latest.getMomentaneWirkleistung().doubleValue();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
