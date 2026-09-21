package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.NodeFieldDescriptor;
import com.household.manager.flowengine.NodeFieldType;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.NodeConfig;
import com.household.manager.sun.SunDayTimes;
import com.household.manager.sun.SunEvent;
import com.household.manager.sun.SunTimesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Trigger „zu einem Sonnenereignis, optional mit Versatz". Plant sich nach jedem
 * Feuern selbst fuer das naechste Vorkommen neu, deshalb greifen geaenderte
 * Koordinaten spaetestens am Folgetag. Geplant wird ein {@link Instant}
 * (zeitumstellungssicher), gerechnet mit dem {@link Clock}-Bean.
 *
 * <p>Ohne konfiguriertes Zuhause bricht der Deploy nicht ab: der Node versucht es
 * stuendlich erneut (Warnung im Log) — der Flow soll sich anlegen lassen, bevor die
 * Koordinaten stehen (dieselbe Toleranz wie {@code helper-set}).
 *
 * <p>Verpasste Ereignisse waehrend eines Backend-Ausfalls werden nicht nachgefeuert
 * (wie {@code schedule-trigger}); wer den Zustand braucht, baut auf {@code sensor.sun}.
 *
 * <p>Das {@code cancelled}-Flag genuegt als Boolean, weil {@code FlowRegistry} je Deploy
 * einen neuen {@link NodeContext} mit eigenem {@code state()} erzeugt: das Feuer-Runnable
 * der alten Generation haelt die alte {@code ctx} und sieht dort das Flag, die neue
 * Generation startet mit leerem State. Waere der State je ueber Re-Deploys geteilt,
 * braeuchte es einen Generations-Token statt eines Booleans.
 */
@Component
@Slf4j
public class SunTriggerHandler implements TriggerNodeHandler {

    static final String EVENT = "event";
    static final String OFFSET = "offsetMinutes";
    static final Duration RETRY_WITHOUT_HOME = Duration.ofMinutes(60);
    private static final String STATE_FUTURE = "future";
    static final String STATE_CANCELLED = "cancelled";

    private final SunTimesService sunTimes;
    private final Clock clock;

    public SunTriggerHandler(SunTimesService sunTimes, Clock clock) {
        this.sunTimes = sunTimes;
        this.clock = clock;
    }

    @Override
    public String type() {
        return "sun-trigger";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public Optional<String> watchedEntityId(NodeConfig config) {
        return Optional.empty();
    }

    @Override
    public List<NodeFieldDescriptor> fields() {
        return List.of(
                NodeFieldDescriptor.enumField(EVENT, "Ereignis", true, SunEvent.keys()),
                NodeFieldDescriptor.field(OFFSET, "Versatz in Minuten (-240 … 240)", NodeFieldType.NUMBER, false));
    }

    @Override
    public List<String> validate(NodeConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.string(EVENT).isEmpty()) {
            errors.add(EVENT + " fehlt");
        } else if (SunEvent.fromKey(config.string(EVENT).get()).isEmpty()) {
            errors.add(EVENT + " ist keines von " + SunEvent.keys() + ": '" + config.string(EVENT).get() + "'");
        }
        parseOffset(config, errors);
        return errors;
    }

    @Override
    public Runnable register(NodeConfig config, NodeContext ctx) {
        SunEvent event = SunEvent.fromKey(config.string(EVENT).orElseThrow()).orElseThrow();
        List<String> errors = new ArrayList<>();
        Optional<Integer> offset = parseOffset(config, errors);
        if (!errors.isEmpty()) {
            // setEnabled/Bootstrap deployen die gespeicherte Definition ohne erneute
            // Validierung — ein kaputter Versatz darf nicht still zu 0 werden.
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        scheduleNext(event, offset.orElse(0), ZonedDateTime.now(clock), ctx);
        return () -> {
            ctx.state().put(STATE_CANCELLED, Boolean.TRUE);
            cancelCurrent(ctx);
        };
    }

    /**
     * Plant das erste Vorkommen von {@code event + offset} nach {@code after}.
     * Ohne Sonnenzeiten (kein Zuhause) stattdessen einen Wiederholungsversuch.
     */
    private void scheduleNext(SunEvent event, int offset, ZonedDateTime after, NodeContext ctx) {
        if (isCancelled(ctx)) {
            return;
        }
        Optional<ZonedDateTime> next = nextOccurrence(event, offset, after);
        Runnable task;
        Instant at;
        if (next.isPresent()) {
            ZonedDateTime scheduledFor = next.get();
            at = scheduledFor.toInstant();
            task = () -> fire(event, offset, scheduledFor, ctx);
        } else {
            log.warn("sun-trigger (Flow {}, Node {}): keine Sonnenzeiten (kein Zuhause konfiguriert?) – naechster Versuch in {} min",
                    ctx.flowId(), ctx.nodeId(), RETRY_WITHOUT_HOME.toMinutes());
            at = after.toInstant().plus(RETRY_WITHOUT_HOME);
            task = () -> scheduleNext(event, offset, ZonedDateTime.now(clock), ctx);
        }
        ScheduledFuture<?> future = ctx.scheduler().schedule(task, at);
        ctx.state().put(STATE_FUTURE, future);
        if (isCancelled(ctx)) {
            // Cleanup lief zwischen Pruefung und Einplanung: kein Geister-Timer hinterlassen.
            future.cancel(false);
        }
    }

    private void fire(SunEvent event, int offset, ZonedDateTime scheduledFor, NodeContext ctx) {
        if (isCancelled(ctx)) {
            return;
        }
        FlowMessage message = FlowMessage.of(Map.of(
                "sunEvent", event.key(),
                "offsetMinutes", offset,
                "scheduledFor", scheduledFor.toLocalDateTime(),
                "timestamp", LocalDateTime.now(clock),
                "triggerNodeId", ctx.nodeId()));
        try {
            ctx.emit(0, message);
        } catch (RuntimeException ex) {
            // ctx.emit kann bei voller Queue/Shutdown werfen (TaskRejectedException); ein
            // Einmal-Task des TaskScheduler propagiert das nur ins nie abgefragte Future
            // und loggt nichts von selbst. Ohne diesen Fang wuerde die Neuplanung unten
            // uebersprungen und der Trigger stuende dauerhaft und lautlos still.
            log.warn("sun-trigger (Flow {}, Node {}): Feuern fehlgeschlagen: {}", ctx.flowId(), ctx.nodeId(), ex.getMessage());
            ctx.debug("ERROR: " + ex.getMessage(), message);
        }
        // Nie vor dem gerade gefeuerten Zeitpunkt weitersuchen — der Scheduler darf
        // Millisekunden frueh dran sein, sonst wuerde dasselbe Ereignis erneut geplant.
        ZonedDateTime now = ZonedDateTime.now(clock);
        scheduleNext(event, offset, now.isAfter(scheduledFor) ? now : scheduledFor, ctx);
    }

    /** Heute, morgen und uebermorgen decken auch einen negativen Versatz ab, der das heutige Ereignis schon vorbeischiebt. */
    Optional<ZonedDateTime> nextOccurrence(SunEvent event, int offset, ZonedDateTime after) {
        LocalDate day = after.withZoneSameInstant(clock.getZone()).toLocalDate();
        for (int d = 0; d <= 2; d++) {
            Optional<SunDayTimes> times = sunTimes.timesFor(day.plusDays(d));
            if (times.isEmpty()) {
                return Optional.empty();
            }
            ZonedDateTime candidate = times.get().timeOf(event).plusMinutes(offset);
            if (candidate.isAfter(after)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isCancelled(NodeContext ctx) {
        return Boolean.TRUE.equals(ctx.state().get(STATE_CANCELLED));
    }

    private static void cancelCurrent(NodeContext ctx) {
        if (ctx.state().get(STATE_FUTURE) instanceof ScheduledFuture<?> future) {
            future.cancel(false);
        }
    }

    private static Optional<Integer> parseOffset(NodeConfig config, List<String> errors) {
        Optional<String> raw = config.string(OFFSET).map(String::trim).filter(s -> !s.isEmpty());
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        int offset;
        try {
            offset = Integer.parseInt(raw.get());
        } catch (NumberFormatException ex) {
            errors.add(OFFSET + " ist keine ganze Zahl: '" + raw.get() + "'");
            return Optional.empty();
        }
        if (Math.abs(offset) > SunEvent.MAX_OFFSET_MINUTES) {
            errors.add(OFFSET + " muss zwischen -" + SunEvent.MAX_OFFSET_MINUTES + " und "
                    + SunEvent.MAX_OFFSET_MINUTES + " liegen: " + offset);
            return Optional.empty();
        }
        return Optional.of(offset);
    }
}
