package com.household.manager.blaulicht;

import com.household.manager.blaulicht.source.BlaulichtFetchException;
import com.household.manager.blaulicht.source.BlaulichtSource;
import com.household.manager.blaulicht.source.NinaSource;
import com.household.manager.blaulicht.source.PresseportalSource;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pollt die Quellen getrennt (Presseportal alle 15 min, NINA alle 5 min), jede für sich fehlertolerant:
 * eine gestörte Quelle meldet {@code reportFailure} auf der Job-Tafel, die andere läuft weiter. Neue
 * NINA-Warnungen feuern sofort {@code event.blaulicht_nina_warning}. Wirft nie.
 */
@Service
@Slf4j
public class BlaulichtPollingService {

    private final PresseportalSource presseportal;
    private final NinaSource nina;
    private final BlaulichtIngestService ingest;
    private final BlaulichtQueryService query;
    private final BlaulichtEntityReporter reporter;
    private final BlaulichtProperties properties;
    private final SystemJobMonitor jobMonitor;

    public BlaulichtPollingService(PresseportalSource presseportal, NinaSource nina, BlaulichtIngestService ingest,
                                   BlaulichtQueryService query, BlaulichtEntityReporter reporter,
                                   BlaulichtProperties properties, SystemJobMonitor jobMonitor) {
        this.presseportal = presseportal;
        this.nina = nina;
        this.ingest = ingest;
        this.query = query;
        this.reporter = reporter;
        this.properties = properties;
        this.jobMonitor = jobMonitor;
    }

    @Scheduled(fixedDelayString = "${blaulicht.presseportal.poll-interval-ms:900000}",
            initialDelayString = "${blaulicht.presseportal.initial-delay-ms:90000}")
    public void pollPresseportal() {
        poll(presseportal);
    }

    @Scheduled(fixedDelayString = "${blaulicht.nina.poll-interval-ms:300000}",
            initialDelayString = "${blaulicht.nina.initial-delay-ms:60000}")
    public void pollNina() {
        List<BlaulichtEvent> saved = poll(nina);
        saved.forEach(reporter::fireNinaWarning);
    }

    private List<BlaulichtEvent> poll(BlaulichtSource source) {
        if (!properties.isEnabled() || !source.enabled()) {
            jobMonitor.reportInactive("Blaulicht-Quelle " + source.key() + " abgeschaltet");
            return List.of();
        }
        try {
            List<BlaulichtEvent> saved = ingest.ingest(source.fetchNew());
            if (!saved.isEmpty()) {
                log.info("Blaulicht {}: {} neue Fälle", source.key(), saved.size());
            }
            refreshOverview();
            return saved;
        } catch (BlaulichtFetchException e) {
            jobMonitor.reportFailure(source.key() + ": " + e.getMessage());
            log.warn("Blaulicht {}: {}", source.key(), e.getMessage());
        } catch (RuntimeException e) {
            jobMonitor.reportFailure(source.key() + ": " + e.getMessage());
            log.warn("Blaulicht {} fehlgeschlagen", source.key(), e);
        }
        // Auch bei gestörter Quelle: die Übersicht rechnet aus dem gespeicherten Stand.
        refreshOverview();
        return List.of();
    }

    private void refreshOverview() {
        try {
            reporter.reportOverview(query.briefing());
        } catch (RuntimeException e) {
            log.warn("Blaulicht: Übersicht nicht berechenbar", e);
        }
    }
}
