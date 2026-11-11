package com.household.manager.laundry;

import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorContext;
import com.household.manager.entitystate.EntityStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** Entscheidet, wann das Wandtablet fragt, ob Waesche aufgehaengt wurde. Wirft nie. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LaundryTriggerListener {

    private static final String STATE_ON = "on";
    private static final String STATE_OFF = "off";
    private static final String FLOW_ACTOR_PREFIX = "FLOW:";

    private final LaundryProperties properties;
    private final LaundryClimateReader climateReader;
    private final LaundryDryingService service;
    private final Clock clock;

    // Erinnerung an das letzte "fertig"; Zugriff nur ueber this, Events kommen von mehreren Threads
    private LocalDateTime finishedAt;
    private double finishedHumidity;
    private boolean finishedKnown;

    @EventListener
    public void onStateChanged(EntityStateChangedEvent event) {
        try {
            if (!properties.isEnabled() || event == null || event.entityId() == null) {
                return;
            }
            String id = event.entityId();
            if (id.equals(properties.getSwitchEntity())) {
                onSwitch(event);
            } else if (id.equals(properties.getFinishedHelper())) {
                onFinished(event);
            } else if (id.equals(properties.getRunningHelper())) {
                onRunning(event);
            } else if (id.equals(properties.getHumidityEntity())) {
                onHumidity(event);
            }
        } catch (Exception ex) {
            log.warn("Laundry trigger failed for {}", event == null ? null : event.entityId(), ex);
        }
    }

    private void onSwitch(EntityStateChangedEvent event) {
        if (!STATE_ON.equals(event.oldState()) || !STATE_OFF.equals(event.newState()) || isFlowDriven()) {
            return;
        }
        climateReader.current().ifPresent(
                climate -> service.ask(LaundryTrigger.SWITCH_OFF, climate.humidity()));
    }

    private static boolean isFlowDriven() {
        AuditActor actor = AuditActorContext.get();
        return actor != null && actor.name() != null && actor.name().startsWith(FLOW_ACTOR_PREFIX);
    }

    private synchronized void onFinished(EntityStateChangedEvent event) {
        if (!STATE_ON.equals(event.newState())) {
            return;
        }
        // Ein spaeteres "fertig" ist ein neuer Waschgang: ein frueheres "Nein" sperrt nicht mehr
        service.onNewWash();
        var climate = climateReader.current();
        if (climate.isEmpty()) {
            finishedKnown = false;
            return;
        }
        finishedHumidity = climate.get().humidity();
        finishedAt = LocalDateTime.now(clock);
        finishedKnown = true;
    }

    private synchronized void onRunning(EntityStateChangedEvent event) {
        if (!STATE_ON.equals(event.newState())) {
            return;
        }
        finishedKnown = false;
        service.onNewWash();
    }

    private void onHumidity(EntityStateChangedEvent event) {
        double humidity;
        try {
            humidity = Double.parseDouble(event.newState().trim());
        } catch (RuntimeException ex) {
            return;
        }
        if (!Double.isFinite(humidity)) {
            return;
        }
        double baseline;
        synchronized (this) {
            if (!finishedKnown) {
                return;
            }
            if (LocalDateTime.now(clock).isAfter(finishedAt.plus(LaundryRules.RISE_WINDOW))) {
                finishedKnown = false;
                return;
            }
            if (humidity - finishedHumidity < LaundryRules.HUMIDITY_RISE_PP) {
                return;
            }
            baseline = finishedHumidity;
            finishedKnown = false;
        }
        service.ask(LaundryTrigger.HUMIDITY_RISE, baseline);
    }
}
