package com.household.manager.tesla;

import com.household.manager.systemlog.SystemJobMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Minütlicher Takt; abgerufen wird nur, wenn das auf der Admin-Seite eingestellte
 * Intervall (Default 5 min) abgelaufen ist. Wirft nie.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeslaPollingService {

    private final TeslaVehicleService vehicleService;
    private final SystemJobMonitor jobMonitor;

    @Scheduled(fixedDelayString = "${tesla.tick-ms:60000}", initialDelayString = "${tesla.initial-delay-ms:40000}")
    public void tick() {
        if (!vehicleService.isConfigured()) {
            jobMonitor.reportInactive("Tesla nicht eingerichtet");
            return;
        }
        if (!vehicleService.isDue()) {
            return;
        }
        try {
            vehicleService.refresh();
        } catch (Exception ex) {
            log.warn("Tesla-Abruf fehlgeschlagen: {}", ex.getMessage());
            jobMonitor.reportFailure(ex.getMessage());
        }
    }
}
