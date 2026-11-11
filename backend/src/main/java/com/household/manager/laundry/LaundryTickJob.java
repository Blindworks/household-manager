package com.household.manager.laundry;

import com.household.manager.systemlog.SystemJobMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Minuetlicher Takt der Waeschetrocknung; wirft nie. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LaundryTickJob {

    private final LaundryDryingService dryingService;
    private final LaundryProperties properties;
    private final SystemJobMonitor jobMonitor;

    @Scheduled(fixedRate = 60_000)
    public void run() {
        if (!properties.isEnabled()) {
            jobMonitor.reportInactive("Wäschetrocknung deaktiviert");
            return;
        }
        try {
            dryingService.tick();
        } catch (Exception ex) {
            log.warn("Waeschetrocknung: Takt fehlgeschlagen: {}", ex.getMessage());
            jobMonitor.reportFailure(ex.getMessage());
        }
    }
}
