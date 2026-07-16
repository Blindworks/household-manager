package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityUsage;
import com.household.manager.repository.EntityUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Zählt, wie oft und wann eine Entität zuletzt geschaltet wurde.
 * Grundlage für die nutzungsbasierte Sortierung der Schalter-Kachel.
 */
@Service
@RequiredArgsConstructor
public class EntityUsageService {

    private final EntityUsageRepository repository;

    /** Zählt einen erfolgreichen Schaltvorgang und legt den Zähler bei Bedarf an. */
    @Transactional
    public EntityUsage recordToggle(String entityId) {
        EntityUsage usage = repository.findByEntityId(entityId)
                .orElseGet(() -> EntityUsage.builder().entityId(entityId).toggleCount(0).build());
        usage.setToggleCount(usage.getToggleCount() + 1);
        usage.setLastToggledAt(LocalDateTime.now());
        return repository.save(usage);
    }

    /** Nutzungsdaten der angefragten Entitäten, nach Entity-ID indiziert. */
    @Transactional(readOnly = true)
    public Map<String, EntityUsage> usageFor(Collection<String> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByEntityIdIn(entityIds).stream()
                .collect(Collectors.toMap(EntityUsage::getEntityId, Function.identity()));
    }
}
