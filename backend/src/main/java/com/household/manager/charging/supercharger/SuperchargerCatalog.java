package com.household.manager.charging.supercharger;

import com.household.manager.charging.ChargingProperties;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Haelt die zuletzt erfolgreich geladene Supercharger-Standortliste nur im Speicher. Taeglich neu,
 * einmal kurz nach dem Start. Wirft nie; bei einem Fehler bleibt der alte Stand stehen.
 * Uebernommen werden nur eroeffnete oder im Ausbau befindliche Standorte mit Koordinaten.
 */
@Service
@Slf4j
public class SuperchargerCatalog {

    private static final Set<String> USABLE_STATUS = Set.of("OPEN", "EXPANDING");

    private final ChargingProperties properties;
    private final SuperchargeInfoClient client;
    private final SystemJobMonitor jobMonitor;

    private volatile List<SuperchargerSite> sites = List.of();

    public SuperchargerCatalog(ChargingProperties properties, SuperchargeInfoClient client,
                               SystemJobMonitor jobMonitor) {
        this.properties = properties;
        this.client = client;
        this.jobMonitor = jobMonitor;
    }

    public List<SuperchargerSite> sites() {
        return sites;
    }

    /** Erster Abruf kurz nach dem Start, ohne den Start zu blockieren. */
    @Scheduled(initialDelayString = "${charging.initial-delay-ms:25000}", fixedDelay = Long.MAX_VALUE)
    public void scheduledInitialLoad() {
        refresh();
    }

    @Scheduled(cron = "0 15 4 * * *")
    public void scheduledDailyReload() {
        refresh();
    }

    /** Laedt die Liste neu; wirft nie, der alte Stand bleibt bei einem Fehler erhalten. */
    public void refresh() {
        if (!properties.isEnabled() || !properties.getSupercharger().isEnabled()) {
            jobMonitor.reportInactive("Supercharger-Standorte deaktiviert");
            return;
        }
        try {
            List<SuperchargerSite> loaded = client.fetchSites().stream()
                    .filter(s -> s.lat() != null && s.lon() != null)
                    .filter(s -> USABLE_STATUS.contains(s.status()))
                    .toList();
            sites = loaded;
            log.info("Supercharger-Standorte geladen: {}", loaded.size());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Supercharger-Abruf unterbrochen");
        } catch (Exception ex) {
            log.warn("Supercharger-Standorte konnten nicht geladen werden: {}", ex.getMessage());
            jobMonitor.reportFailure(String.valueOf(ex.getMessage()));
        }
    }
}
