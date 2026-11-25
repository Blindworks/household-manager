package com.household.manager.blaulicht;

import com.household.manager.blaulicht.dto.BlaulichtBriefingDto;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Minütlicher Takt für Briefing und Nachtrag: in der Minute von {@code nightEnd} feuert das Morgen-Briefing
 * (immer, auch bei ruhiger Nacht), in der Minute von {@code addendumAt} der Nachtrag (nur wenn es einen
 * gibt), zu {@code nightStart} wird die Übersicht für die neue Nacht aufgefrischt. Wie ein Cron: war das
 * Backend genau in der Minute nicht da, entfällt das Ereignis (nichts wird nachgefeuert). Wirft nie.
 */
@Component
@Slf4j
public class BlaulichtBriefingJob {

    private final BlaulichtQueryService query;
    private final BlaulichtEntityReporter reporter;
    private final BlaulichtProperties properties;
    private final SystemJobMonitor jobMonitor;
    private final Clock clock;

    public BlaulichtBriefingJob(BlaulichtQueryService query, BlaulichtEntityReporter reporter,
                                BlaulichtProperties properties, SystemJobMonitor jobMonitor, Clock clock) {
        this.query = query;
        this.reporter = reporter;
        this.properties = properties;
        this.jobMonitor = jobMonitor;
        this.clock = clock;
    }

    @Scheduled(cron = "5 * * * * *", zone = "Europe/Berlin")
    public void tick() {
        if (!properties.isEnabled()) {
            jobMonitor.reportInactive("Blaulicht abgeschaltet");
            return;
        }
        LocalTime minute = LocalTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
        BlaulichtProperties.Briefing config = properties.getBriefing();
        boolean morning = minute.equals(config.getNightEnd().truncatedTo(ChronoUnit.MINUTES));
        boolean addendum = minute.equals(config.getAddendumAt().truncatedTo(ChronoUnit.MINUTES));
        boolean evening = minute.equals(config.getNightStart().truncatedTo(ChronoUnit.MINUTES));
        if (!morning && !addendum && !evening) {
            return;
        }
        try {
            BlaulichtBriefingDto briefing = query.briefing();
            reporter.reportOverview(briefing);
            if (morning) {
                reporter.fireMorningBriefing(briefing);
            }
            if (addendum && briefing.addendumCount() > 0) {
                reporter.fireAddendum(briefing);
            }
        } catch (RuntimeException e) {
            jobMonitor.reportFailure("Briefing: " + e.getMessage());
            log.warn("Blaulicht-Briefing fehlgeschlagen", e);
        }
    }
}
