package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.EntityStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Facade der Entity-/State-Schicht und einzige Schreibstelle.
 * <p>
 * {@link #reportState} ist bewusst absolut fehlertolerant: Persistenz- oder
 * Listener-Fehler werden geloggt und niemals an die aufrufende Integration
 * weitergegeben (Polling/MQTT/Schaltbefehle dürfen dadurch nie brechen).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EntityStateService {

    private final EntityStateWriter writer;
    private final EntityStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Meldet einen Entitätszustand (Upsert). Publiziert bei Wertänderung ein
     * {@link EntityStateChangedEvent} nach erfolgreichem Commit.
     */
    public void reportState(EntityStateUpdate update) {
        try {
            Optional<EntityStateChangedEvent> event = writer.upsert(update);
            event.ifPresent(this::publishSafely);
        } catch (Exception ex) {
            log.warn("Failed to report entity state for {}: {}", update.entityId(), ex.getMessage());
        }
    }

    /**
     * Meldet ein Ereignis einer EVENT-Entität (z. B. Zigbee-Tastendruck).
     * Publiziert bei JEDEM Aufruf ein {@link EntityEventFired} — auch bei
     * wiederholt gleicher Aktion. Gleiche Fehlertoleranz wie {@link #reportState}.
     */
    public void reportEvent(EntityStateUpdate update) {
        try {
            EntityEventFired event = writer.upsertEvent(update);
            publishSafely(event);
        } catch (Exception ex) {
            log.warn("Failed to report entity event for {}: {}", update.entityId(), ex.getMessage());
        }
    }

    private void publishSafely(Object event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception ex) {
            log.warn("Entity event listener failed: {}", ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<EntityState> getAll() {
        return repository.findAllByOrderByEntityIdAsc();
    }

    @Transactional(readOnly = true)
    public List<EntityState> find(EntityDomain domain, EntitySource source) {
        if (domain != null && source != null) {
            return repository.findByDomainAndSourceOrderByEntityIdAsc(domain, source);
        }
        if (domain != null) {
            return repository.findByDomainOrderByEntityIdAsc(domain);
        }
        if (source != null) {
            return repository.findBySourceOrderByEntityIdAsc(source);
        }
        return repository.findAllByOrderByEntityIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<EntityState> getByEntityId(String entityId) {
        return repository.findByEntityId(entityId);
    }

    @Transactional
    public boolean deleteByEntityId(String entityId) {
        if (repository.findByEntityId(entityId).isEmpty()) {
            return false;
        }
        repository.deleteByEntityId(entityId);
        return true;
    }

    /**
     * Setzt oder löscht den benutzerdefinierten Kurznamen einer Entität.
     * Leerer/nur-Whitespace-Wert löscht den Kurznamen (Fallback auf friendlyName).
     * Bewusst ein direkter, benutzerinitiierter Schreibpfad – nicht der
     * fehlertolerante reportState/upsert-Pfad der Integrationen.
     *
     * @return aktualisierte Entität, oder leer wenn die entityId unbekannt ist
     */
    @Transactional
    public Optional<EntityState> setCustomName(String entityId, String customName) {
        return repository.findByEntityId(entityId).map(entity -> {
            entity.setCustomName(normalizeCustomName(customName));
            return repository.save(entity);
        });
    }

    private String normalizeCustomName(String customName) {
        if (customName == null) {
            return null;
        }
        String trimmed = customName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
