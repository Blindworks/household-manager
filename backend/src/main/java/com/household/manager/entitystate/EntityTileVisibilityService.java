package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityTileVisibility;
import com.household.manager.repository.EntityTileVisibilityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Verwaltet die benutzergepflegten Sichtbarkeitsregeln von Entitäten auf
 * Dashboard-Kacheln. Kein Eintrag bedeutet {@link TileVisibility#AUTO};
 * AUTO wird deshalb nie gespeichert, sondern löscht die Regel.
 */
@Service
@RequiredArgsConstructor
public class EntityTileVisibilityService {

    private final EntityTileVisibilityRepository repository;

    /** Setzt die Regel einer Entität für eine Kachel; AUTO entfernt sie. */
    @Transactional
    public void setVisibility(String entityId, String tileKey, TileVisibility visibility) {
        if (visibility == TileVisibility.AUTO) {
            repository.deleteByEntityIdAndTileKey(entityId, tileKey);
            return;
        }
        EntityTileVisibility rule = repository.findByEntityIdAndTileKey(entityId, tileKey)
                .orElseGet(() -> EntityTileVisibility.builder()
                        .entityId(entityId)
                        .tileKey(tileKey)
                        .build());
        rule.setVisibility(visibility);
        rule.setUpdatedAt(LocalDateTime.now());
        repository.save(rule);
    }

    /** Alle expliziten Regeln einer Kachel, nach Entity-ID indiziert. */
    @Transactional(readOnly = true)
    public Map<String, TileVisibility> tileRules(String tileKey) {
        return repository.findByTileKey(tileKey).stream()
                .collect(Collectors.toMap(
                        EntityTileVisibility::getEntityId,
                        EntityTileVisibility::getVisibility));
    }

    /** Regeln der angefragten Entitäten als Map entityId → (tileKey → visibility). */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> visibilityByEntity(Collection<String> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        return repository.findByEntityIdIn(entityIds).stream()
                .collect(Collectors.groupingBy(
                        EntityTileVisibility::getEntityId,
                        Collectors.toMap(
                                EntityTileVisibility::getTileKey,
                                rule -> rule.getVisibility().name())));
    }
}
