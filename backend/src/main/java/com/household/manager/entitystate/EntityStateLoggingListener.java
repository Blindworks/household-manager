package com.household.manager.entitystate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loggt jede Zustandsänderung. Einziger Event-Konsument in Ausbaustufe 1;
 * die spätere Regel-Engine hört auf dieselben Events.
 */
@Component
@Slf4j
public class EntityStateLoggingListener {

    @EventListener
    public void onStateChanged(EntityStateChangedEvent event) {
        log.debug("Entity {} changed: {} -> {}", event.entityId(), event.oldState(), event.newState());
    }
}
