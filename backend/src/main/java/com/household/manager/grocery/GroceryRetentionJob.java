package com.household.manager.grocery;

import com.household.manager.grocery.entity.GroceryMatch;
import com.household.manager.repository.GroceryMatchRepository;
import com.household.manager.repository.GroceryOfferRepository;
import com.household.manager.repository.GroceryPriceObservationRepository;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Nächtliche Aufräumrunde (03:35): Angebote 28 Tage nach Ablauf (ohne Enddatum 28 Tage nach dem Sammeln),
 * Preisbeobachtungen und Claude-Urteile nach
 * 365 Tagen, Laufprotokolle nach 90 Tagen. Nutzerurteile bleiben. Jede Tabelle isoliert; wirft nie.
 */
@Slf4j
@Component
public class GroceryRetentionJob {

    static final int OFFER_DAYS_AFTER_EXPIRY = 28;
    static final int OBSERVATION_DAYS = 365;
    static final int CLAUDE_VERDICT_DAYS = 365;
    static final int RUN_LOG_DAYS = 90;

    private final GroceryOfferRepository offers;
    private final GroceryPriceObservationRepository observations;
    private final GroceryMatchRepository matches;
    private final GroceryRunLogService runLog;
    private final SystemJobMonitor jobMonitor;
    private final Clock clock;

    public GroceryRetentionJob(GroceryOfferRepository offers, GroceryPriceObservationRepository observations,
                               GroceryMatchRepository matches, GroceryRunLogService runLog,
                               SystemJobMonitor jobMonitor, Clock clock) {
        this.offers = offers;
        this.runLog = runLog;
        this.observations = observations;
        this.matches = matches;
        this.jobMonitor = jobMonitor;
        this.clock = clock;
    }

    @Scheduled(cron = "0 35 3 * * *", zone = "Europe/Berlin")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        List<String> failures = new ArrayList<>();
        attempt("Angebote", failures,
                () -> offers.deleteExpiredBefore(today.minusDays(OFFER_DAYS_AFTER_EXPIRY)));
        attempt("Angebote ohne Enddatum", failures,
                () -> offers.deleteUndatedCollectedBefore(now.minusDays(OFFER_DAYS_AFTER_EXPIRY)));
        attempt("Preisbeobachtungen", failures,
                () -> observations.deleteObservedBefore(now.minusDays(OBSERVATION_DAYS)));
        attempt("Claude-Urteile", failures, () -> matches.deleteDecidedBefore(GroceryMatch.DecidedBy.CLAUDE,
                now.minusDays(CLAUDE_VERDICT_DAYS)));
        attempt("Laufprotokolle", failures, () -> runLog.deleteStartedBefore(now.minusDays(RUN_LOG_DAYS)));
        if (!failures.isEmpty()) {
            jobMonitor.reportFailure("Aufräumen fehlgeschlagen: " + String.join(", ", failures));
        }
    }

    private interface Deletion {
        int delete();
    }

    private void attempt(String label, List<String> failures, Deletion deletion) {
        try {
            int removed = deletion.delete();
            if (removed > 0) {
                log.info("Supermarkt-Aufräumen: {} {} gelöscht", removed, label);
            }
        } catch (RuntimeException e) {
            log.warn("Supermarkt-Aufräumen ({}) fehlgeschlagen: {}", label, e.getMessage());
            failures.add(label);
        }
    }
}
