package com.household.manager.service;

import com.household.manager.dto.EnergyLiveDto;
import com.household.manager.dto.TasmotaElectricityLiveResponse;
import com.household.manager.shelly.ShellyService;
import com.household.manager.shelly.dto.ShellyStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Kombiniert die Live-Daten der beiden Balkonkraftwerk-Shellys und des Tasmota-Stromzählers
 * zu einem Hausverbrauchs-DTO und stellt sie als SSE-Stream bereit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnergyCalculationService {

    private final ShellyService shellyService;
    private final TasmotaElectricityLiveService tasmotaLiveService;
    private final TaskScheduler taskScheduler;

    @Value("${energy.shelly.bkw-alt-name:Balkonkraftwerk-Alt}")
    private String bkwAltName;

    @Value("${energy.shelly.bkw-neu-name:Balkonkraftwerk-Neu}")
    private String bkwNeuName;

    @Value("${energy.live.interval-ms:2000}")
    private long intervalMs;

    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> scheduledFuture;

    public EnergyLiveDto calculateCurrent() {
        List<ShellyStatusDto> devices = safeShellyStatus();
        ShellyStatusDto bkwAlt = findByName(devices, bkwAltName);
        ShellyStatusDto bkwNeu = findByName(devices, bkwNeuName);
        TasmotaElectricityLiveResponse tasmota = tasmotaLiveService.fetchCurrent();

        BigDecimal bkwAltW = powerOf(bkwAlt);
        BigDecimal bkwNeuW = powerOf(bkwNeu);
        BigDecimal pvTotalW = bkwAltW.add(bkwNeuW);

        boolean tasmotaReachable = tasmota != null && tasmota.getMomentaneWirkleistung() != null;
        BigDecimal gridW = tasmotaReachable ? tasmota.getMomentaneWirkleistung() : BigDecimal.ZERO;

        BigDecimal hausverbrauchW = pvTotalW.add(gridW).setScale(2, RoundingMode.HALF_UP);

        boolean shellyReachable = (bkwAlt != null && bkwAlt.reachable()) || (bkwNeu != null && bkwNeu.reachable());

        return EnergyLiveDto.builder()
                .timestamp(LocalDateTime.now())
                .bkwAltW(bkwAltW.setScale(2, RoundingMode.HALF_UP))
                .bkwNeuW(bkwNeuW.setScale(2, RoundingMode.HALF_UP))
                .pvTotalW(pvTotalW.setScale(2, RoundingMode.HALF_UP))
                .gridW(gridW.setScale(2, RoundingMode.HALF_UP))
                .hausverbrauchW(hausverbrauchW)
                .gridImporting(gridW.signum() >= 0)
                .shellyReachable(shellyReachable)
                .tasmotaReachable(tasmotaReachable)
                .build();
    }

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> removeEmitter(emitter));
        emitter.onError(e -> removeEmitter(emitter));

        ensureSchedulerRunning();
        return emitter;
    }

    private List<ShellyStatusDto> safeShellyStatus() {
        try {
            return shellyService.getAllDevicesStatus();
        } catch (Exception ex) {
            log.debug("Shelly status fetch failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private ShellyStatusDto findByName(List<ShellyStatusDto> devices, String name) {
        return devices.stream()
                .filter(d -> name.equals(d.deviceName()))
                .findFirst()
                .orElse(null);
    }

    private BigDecimal powerOf(ShellyStatusDto device) {
        if (device == null || !device.reachable() || device.power() == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(device.power());
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
            log.info("Energy live polling started ({} ms)", delay);
        }
    }

    private void stopScheduler() {
        synchronized (scheduleLock) {
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
                scheduledFuture = null;
                log.info("Energy live polling stopped");
            }
        }
    }

    private void pollAndBroadcast() {
        if (emitters.isEmpty()) {
            stopScheduler();
            return;
        }

        EnergyLiveDto dto;
        try {
            dto = calculateCurrent();
        } catch (Exception ex) {
            log.debug("Energy calculation failed: {}", ex.getMessage());
            return;
        }

        emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("live").data(dto));
            } catch (Exception ex) {
                removeEmitter(emitter);
            }
        });
    }
}
