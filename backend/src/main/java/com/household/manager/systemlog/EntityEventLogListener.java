package com.household.manager.systemlog;

import com.household.manager.entitystate.EntityEventFired;
import com.household.manager.entitystate.EntityStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Nimmt Zustandswechsel und Ereignisse der Entitaeten ins Systemprotokoll auf. Rein numerische Wechsel
 * (Messwerte) werden verworfen - sie wuerden das Protokoll fluten; Ausfaelle ("unavailable") bleiben sichtbar.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntityEventLogListener {

    private static final String UNAVAILABLE = "unavailable";

    private final SystemLogWriter writer;

    @EventListener
    public void onStateChanged(EntityStateChangedEvent event) {
        try {
            if (isNumericChange(event.oldState(), event.newState())) {
                return;
            }
            SystemLogStatus status = UNAVAILABLE.equals(event.newState()) ? SystemLogStatus.WARN : SystemLogStatus.INFO;
            writer.write(entry(event.entityId(), status, event.oldState() + " → " + event.newState()));
        } catch (Exception ex) {
            log.debug("Systemprotokoll: Zustandswechsel nicht protokolliert", ex);
        }
    }

    @EventListener
    public void onEventFired(EntityEventFired event) {
        try {
            writer.write(entry(event.entityId(), SystemLogStatus.INFO, event.action()));
        } catch (Exception ex) {
            log.debug("Systemprotokoll: Entity-Ereignis nicht protokolliert", ex);
        }
    }

    static boolean isNumericChange(String oldState, String newState) {
        return isNumeric(oldState) && isNumeric(newState);
    }

    private static boolean isNumeric(String value) {
        if (value == null) {
            return false;
        }
        try {
            new BigDecimal(value.trim());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static SystemLogEvent entry(String entityId, SystemLogStatus status, String message) {
        return SystemLogEvent.builder().kind(SystemLogKind.ENTITY).status(status)
                .source(entityId).title(entityId).message(message).build();
    }
}
