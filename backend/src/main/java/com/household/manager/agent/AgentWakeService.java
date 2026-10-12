package com.household.manager.agent;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entscheidet, WANN der Hausverstand laeuft: Grundtakt, Wecker auf ausgewaehlte Entitaeten und der
 * manuelle Lauf. Drei Regeln tragen das Ganze:
 *
 * <ol>
 *   <li><b>Nie zwei Laeufe gleichzeitig.</b> Zwei parallele Laeufe saehen denselben Hauszustand und
 *       schlugen doppelt vor bzw. schalteten doppelt. Das Lauf-Flag und der Folgelauf-Merker werden
 *       deshalb unter EINEM Lock gesetzt und gelesen — mit zwei unabhaengigen AtomicBooleans gaebe
 *       es ein Fenster, in dem ein ablehnender Aufruf den Merker setzt, nachdem der endende Lauf ihn
 *       schon geprueft hat: der Wecker waere verloren.</li>
 *   <li><b>Kein Wecker geht waehrend eines Laufs verloren</b>, aber beliebig viele Wecker waehrend
 *       eines Laufs ergeben genau EINEN Folgelauf, der alle gesammelten Gruende traegt. Der
 *       Folgelauf wird direkt an den Executor uebergeben, ohne das Lauf-Flag zwischendurch
 *       freizugeben — so kann sich kein anderer Aufruf dazwischenschieben.</li>
 *   <li><b>Bei ausgeschaltetem Betriebsschalter laeuft nichts</b> — geprueft im Moment der
 *       Uebergabe, also auch fuer einen Folgelauf, wenn der Schalter waehrend des Laufs fiel.</li>
 * </ol>
 *
 * <p>Wecker werden entprellt: eine Abwesenheits-Flanke zieht oft Modus-, Licht- und Sonnenwechsel
 * in kurzer Folge nach sich; ein Lauf je Flanke kostete Token und lieferte Vorschlaege auf einem
 * halb umgeschalteten Haus. Der erste Wecker startet die Frist, weitere haengen nur ihren Grund an.
 *
 * <p>Die geplanten und ereignisgetriebenen Einstiege werfen nie; nur {@link #runNow()} meldet
 * Ablehnungen an den Aufrufer (Controller → 400/429).
 */
@Service
@Slf4j
public class AgentWakeService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final String FOLLOW_UP_DETAIL = "Folgelauf";

    private final AgentProperties properties;
    private final AgentSettingsService settings;
    private final AgentRunService runService;
    private final ExecutorService executor;
    private final ScheduledExecutorService debounceScheduler;
    private final Clock clock;

    /** Schuetzt {@link #running}, {@link #followUp} und {@link #followUpReasons}. */
    private final Object runLock = new Object();
    private boolean running;
    private boolean followUp;
    private final List<String> followUpReasons = new ArrayList<>();

    /** Schuetzt {@link #pendingReasons} und {@link #debounceFuture}. */
    private final Object debounceLock = new Object();
    private final List<String> pendingReasons = new ArrayList<>();
    private ScheduledFuture<?> debounceFuture;

    private final AtomicReference<Instant> lastManualRun = new AtomicReference<>();

    public AgentWakeService(AgentProperties properties,
                            AgentSettingsService settings,
                            AgentRunService runService,
                            @Qualifier("agentExecutor") ExecutorService executor,
                            @Qualifier("agentDebounceScheduler") ScheduledExecutorService debounceScheduler,
                            Clock clock) {
        this.properties = properties;
        this.settings = settings;
        this.runService = runService;
        this.executor = executor;
        this.debounceScheduler = debounceScheduler;
        this.clock = clock;
    }

    /** Grundtakt. Wirft nie. */
    @Scheduled(fixedDelayString = "${agent.tick-interval-ms}", initialDelayString = "${agent.initial-delay-ms}")
    public void tick() {
        try {
            submit(new AgentTrigger(AgentTriggerType.SCHEDULED, List.of()));
        } catch (Exception ex) {
            log.warn("Grundtakt des Hausverstands fehlgeschlagen", ex);
        }
    }

    /**
     * Wecker: nur Entitaeten aus {@code agent.wake-entities} zaehlen. Laeuft synchron im
     * publizierenden Thread (Entity-State-Layer) und darf ihn deshalb weder blockieren noch mit
     * einer Exception stoeren — die eigentliche Arbeit passiert nach der Entprellfrist.
     */
    @EventListener
    public void onEntityStateChanged(EntityStateChangedEvent event) {
        try {
            if (!properties.isEnabled() || !properties.getWakeEntities().contains(event.entityId())) {
                return;
            }
            String reason = "%s: %s → %s um %s".formatted(event.entityId(),
                    Objects.toString(event.oldState(), "–"), Objects.toString(event.newState(), "–"),
                    LocalTime.now(clock).format(TIME));
            synchronized (debounceLock) {
                pendingReasons.add(reason);
                if (debounceFuture == null) {
                    debounceFuture = debounceScheduler.schedule(this::fireDebounced,
                            properties.getWakeDebounceMs(), TimeUnit.MILLISECONDS);
                }
            }
        } catch (RejectedExecutionException ex) {
            log.debug("Hausverstand-Wecker verworfen, Scheduler faehrt herunter");
            clearPendingReasons();
        } catch (Exception ex) {
            log.warn("Hausverstand-Wecker fuer {} fehlgeschlagen", event.entityId(), ex);
            clearPendingReasons();
        }
    }

    /**
     * Manueller Lauf, synchron auf dem Aufrufer-Thread (Muster {@code presence/refresh}): wer den
     * Knopf drueckt, bekommt das Ergebnis in derselben Antwort.
     *
     * <p>Reihenfolge der Pruefungen: erst der Mindestabstand (ohne zu reservieren), dann das
     * Lauf-Flag, erst danach wird der Zeitstempel per compareAndSet reserviert (Muster
     * {@code NetworkSpeedtestService}). So verbraucht ein wegen eines laufenden Laufs abgelehnter
     * Klick keinen Mindestabstand, und zwei gleichzeitige Klicks koennen nicht beide durchkommen.
     */
    public AgentRunResult runNow() {
        if (!isOperational()) {
            throw new IllegalStateException("Hausverstand ist ausgeschaltet.");
        }
        Instant now = clock.instant();
        Instant last = lastManualRun.get();
        throwIfTooSoon(last, now);
        synchronized (runLock) {
            if (running) {
                throw new TooManyRequestsException("Es läuft gerade ein Lauf.");
            }
            running = true;
        }
        try {
            if (!lastManualRun.compareAndSet(last, now)) {
                throwIfTooSoon(lastManualRun.get(), now);
                throw new TooManyRequestsException("Bitte warten — letzter manueller Lauf vor 0 s.");
            }
            return runService.run(new AgentTrigger(AgentTriggerType.MANUAL, List.of()));
        } finally {
            finishRun();
        }
    }

    /** Fuer Tests: ob gerade ein Lauf (inklusive eines direkt angehaengten Folgelaufs) aktiv ist. */
    boolean isRunning() {
        synchronized (runLock) {
            return running;
        }
    }

    private void throwIfTooSoon(Instant last, Instant now) {
        if (last == null) {
            return;
        }
        long elapsedMs = Duration.between(last, now).toMillis();
        if (elapsedMs < properties.getManualRunMinIntervalMs()) {
            throw new TooManyRequestsException(
                    "Bitte warten — letzter manueller Lauf vor %d s.".formatted(Math.max(0, elapsedMs / 1000)));
        }
    }

    private void fireDebounced() {
        try {
            List<String> reasons;
            synchronized (debounceLock) {
                reasons = List.copyOf(pendingReasons);
                pendingReasons.clear();
                debounceFuture = null;
            }
            if (!reasons.isEmpty()) {
                submit(new AgentTrigger(AgentTriggerType.WAKE, reasons));
            }
        } catch (Exception ex) {
            log.warn("Entprellter Hausverstand-Wecker fehlgeschlagen", ex);
        }
    }

    private void clearPendingReasons() {
        synchronized (debounceLock) {
            pendingReasons.clear();
            debounceFuture = null;
        }
    }

    /**
     * Uebergibt einen Lauf an den Executor oder merkt ihn als Folgelauf vor, wenn gerade einer
     * laeuft. Bei ausgeschaltetem Schalter wird der Anlass verworfen — nachgeholt wird nichts, der
     * naechste Grundtakt nach dem Einschalten sieht ohnehin den aktuellen Zustand.
     */
    private void submit(AgentTrigger trigger) {
        if (!isOperational()) {
            log.debug("Hausverstand ausgeschaltet, Anlass verworfen: {}", trigger.describe());
            return;
        }
        synchronized (runLock) {
            if (running) {
                followUp = true;
                if (trigger.type() == AgentTriggerType.WAKE) {
                    followUpReasons.addAll(trigger.details());
                }
                return;
            }
            running = true;
        }
        if (!dispatch(trigger)) {
            finishRun();
        }
    }

    private void execute(AgentTrigger trigger) {
        try {
            runService.run(trigger);
        } catch (Exception ex) {
            // AgentRunService wirft vertraglich nie; faengt trotzdem, damit das Flag sicher frei wird.
            log.warn("Hausverstand-Lauf ist unerwartet geworfen", ex);
        } finally {
            finishRun();
        }
    }

    /**
     * Gibt das Lauf-Flag frei — oder haengt, wenn waehrenddessen Anlaesse kamen, genau einen
     * Folgelauf an, ohne das Flag loszulassen. Die Schleife deckt ab, dass ein Folgelauf verworfen
     * wird (Schalter aus, Executor faehrt herunter) und inzwischen schon der naechste vorgemerkt ist.
     */
    private void finishRun() {
        while (true) {
            AgentTrigger next;
            synchronized (runLock) {
                if (!followUp) {
                    running = false;
                    return;
                }
                followUp = false;
                List<String> reasons = followUpReasons.isEmpty()
                        ? List.of(FOLLOW_UP_DETAIL) : List.copyOf(followUpReasons);
                followUpReasons.clear();
                next = new AgentTrigger(AgentTriggerType.WAKE, reasons);
            }
            if (!isOperational()) {
                log.debug("Hausverstand ausgeschaltet, Folgelauf verworfen: {}", next.describe());
            } else if (dispatch(next)) {
                return;
            }
        }
    }

    private boolean dispatch(AgentTrigger trigger) {
        try {
            executor.submit(() -> execute(trigger));
            return true;
        } catch (RejectedExecutionException ex) {
            log.debug("Hausverstand-Lauf verworfen, Executor faehrt herunter: {}", trigger.describe());
            return false;
        }
    }

    /** Deploy-Schalter UND Betriebsschalter; ein Lesefehler gilt als „aus“, nie als „an“. */
    private boolean isOperational() {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            return settings.isActive();
        } catch (Exception ex) {
            log.warn("Betriebsschalter des Hausverstands nicht lesbar, behandle als aus", ex);
            return false;
        }
    }
}
