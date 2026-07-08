package com.household.manager.entitystate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Transaktionaler Upsert für Entitätszustände. REQUIRES_NEW, damit ein Fehler
 * der Spiegel-Schicht niemals die Transaktion der aufrufenden Integration vergiftet.
 * Nur von {@link EntityStateService} aufzurufen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntityStateWriter {

    static final String STATE_UNKNOWN = "unknown";

    private final EntityStateRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Legt die Entität bei Bedarf an und aktualisiert ihren Zustand.
     *
     * @return Event, wenn sich der Zustandswert geändert hat; sonst leer
     */
    // Muss public bleiben: Springs proxy-basiertes @Transactional ignoriert
    // nicht-public Methoden stillschweigend (REQUIRES_NEW wäre lautlos weg).
    // Nur über EntityStateService.reportState aufrufen (Fehlerkapselung).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<EntityStateChangedEvent> upsert(EntityStateUpdate update) {
        LocalDateTime now = LocalDateTime.now();
        String newState = update.state() != null ? update.state() : STATE_UNKNOWN;

        EntityState entity = repository.findByEntityId(update.entityId())
                .orElseGet(() -> EntityState.builder()
                        .entityId(update.entityId())
                        .domain(update.domain())
                        .source(update.source())
                        .sourceRef(update.sourceRef())
                        .state(STATE_UNKNOWN)
                        .lastChanged(now)
                        .build());

        String oldState = entity.getState();
        entity.setFriendlyName(update.friendlyName());
        entity.setAttributes(serializeAttributes(update.attributes()));
        entity.setLastUpdated(now);

        boolean changed = !newState.equals(oldState);
        if (changed) {
            entity.setState(newState);
            entity.setLastChanged(now);
        }
        repository.save(entity);

        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new EntityStateChangedEvent(
                update.entityId(), oldState, newState, update.attributes(), now));
    }

    private String serializeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize entity attributes: {}", ex.getMessage());
            return null;
        }
    }
}
