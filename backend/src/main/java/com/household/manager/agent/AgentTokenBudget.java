package com.household.manager.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Tagesbudget für die Tokens des Hausverstands. Ein Lauf kostet Geld; ohne Deckel könnte eine
 * Wecker-Schleife (flatternde Entität) oder ein Dauerklick auf „Jetzt laufen lassen“ die Rechnung
 * unbemerkt hochtreiben. Gezählt werden Eingabe- plus Ausgabe-Tokens seit Mitternacht (Haushaltszeit).
 *
 * <p><b>Fail-closed:</b> Ist der Verbrauch nicht lesbar, gilt das Budget als erschöpft — ein
 * übersprungener Lauf ist billiger als ein ungedeckelter, und der nächste Takt prüft erneut.
 * Ein bereits laufender Lauf wird nie abgebrochen; das Budget kann deshalb um höchstens einen Lauf
 * überschritten werden. Cache-Lese-Tokens zählt das Protokoll heute nicht mit (leichte Untererfassung).
 *
 * <p>Die Erschöpfung wird pro Tag genau einmal gemeldet (Muster {@link AgentFailureStreak}), sonst
 * wiederholte sich die Nachricht bei jedem verworfenen Takt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentTokenBudget {

    private final AgentProperties properties;
    private final AgentRunRecorder recorder;
    private final AgentNotifier notifier;
    private final Clock clock;

    /** Tag, für den die Erschöpfung schon gemeldet wurde; geschützt durch {@code this}. */
    private LocalDate warnedOn;

    /** Wirft nie. {@code true} = keine weiteren Läufe heute. */
    public boolean isExhausted() {
        long limit = properties.getDailyTokenBudget();
        if (limit <= 0) {
            return false;
        }
        long used;
        try {
            long[] tokens = recorder.tokensToday();
            used = tokens[0] + tokens[1];
        } catch (Exception ex) {
            log.warn("Tokenverbrauch des Hausverstands nicht lesbar, behandle Budget als erschöpft", ex);
            return true;
        }
        if (used < limit) {
            return false;
        }
        warnOncePerDay(limit);
        return true;
    }

    public long budget() {
        return properties.getDailyTokenBudget();
    }

    /** Heutiger Verbrauch (Eingabe + Ausgabe); 0, wenn er nicht lesbar ist. */
    public long usedToday() {
        try {
            long[] tokens = recorder.tokensToday();
            return tokens[0] + tokens[1];
        } catch (Exception ex) {
            log.warn("Tokenverbrauch des Hausverstands nicht lesbar", ex);
            return 0;
        }
    }

    private synchronized void warnOncePerDay(long limit) {
        LocalDate today = LocalDate.now(clock);
        if (today.equals(warnedOn)) {
            return;
        }
        warnedOn = today;
        notifier.sendInfo("Hausverstand: Tagesbudget von " + limit
                + " Tokens erreicht — keine weiteren Läufe bis Mitternacht.");
    }
}
