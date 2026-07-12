package com.household.manager.entitystate;

import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Verwaltet vom Benutzer angelegte, manuell schaltbare Boolean-Entitäten
 * (Helfer wie "Nachtmodus" oder "Haus abgeschlossen").
 * <p>
 * Schreibt ausschließlich über {@link EntityStateService} (die einzige Schreibstelle
 * der Entity-Schicht), damit Events konsistent publiziert werden. Alle schreibenden
 * Operationen sind auf Entitäten mit {@link EntitySource#MANUAL} beschränkt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManualEntityService {

    static final String STATE_ON = "on";
    static final String STATE_OFF = "off";
    private static final String ATTR_ICON = "icon";

    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;

    /**
     * Legt eine neue manuelle Boolean-Entität an. Die Entity-ID wird aus dem Namen
     * abgeleitet und bleibt bei späterem Umbenennen stabil.
     *
     * @throws DuplicateEntityException wenn der Name auf eine bereits existierende ID abbildet
     */
    public EntityState create(String name, String rawState, String icon) {
        String friendlyName = name.trim();
        String entityId = EntityIds.build(EntityDomain.INPUT_BOOLEAN, EntitySource.MANUAL, friendlyName, null);

        if (entityStateService.getByEntityId(entityId).isPresent()) {
            throw new DuplicateEntityException("Entity already exists: " + entityId);
        }

        Map<String, Object> attributes = attributesWithIcon(new LinkedHashMap<>(), icon);
        report(entityId, EntityIds.slug(friendlyName), friendlyName, normalizeState(rawState, STATE_OFF), attributes);

        return reload(entityId);
    }

    /** Setzt den Zustand ("on"/"off") einer bestehenden manuellen Entität. */
    public EntityState setState(String entityId, String rawState) {
        EntityState entity = requireManual(entityId);
        report(entity, normalizeState(rawState, null));
        return reload(entityId);
    }

    /** Schaltet eine manuelle Entität um (on ↔ off). Unbekannte Zustände werden zu "on". */
    public EntityState toggle(String entityId) {
        EntityState entity = requireManual(entityId);
        String next = STATE_ON.equals(entity.getState()) ? STATE_OFF : STATE_ON;
        report(entity, next);
        return reload(entityId);
    }

    /** Benennt eine manuelle Entität um; die Entity-ID bleibt unverändert. */
    public EntityState rename(String entityId, String newName, String icon) {
        EntityState entity = requireManual(entityId);
        Map<String, Object> attributes = attributesWithIcon(currentAttributes(entity), icon);
        report(entity.getEntityId(), entity.getSourceRef(), newName.trim(), entity.getState(), attributes);
        return reload(entityId);
    }

    /** Löscht eine manuelle Entität. */
    public void delete(String entityId) {
        requireManual(entityId);
        entityStateService.deleteByEntityId(entityId);
    }

    private void report(EntityState entity, String newState) {
        report(entity.getEntityId(), entity.getSourceRef(), entity.getFriendlyName(), newState, currentAttributes(entity));
    }

    private void report(String entityId, String sourceRef, String friendlyName, String state, Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(entityId)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef(sourceRef)
                .friendlyName(friendlyName)
                .state(state)
                .attributes(attributes.isEmpty() ? null : attributes)
                .build());
    }

    private EntityState requireManual(String entityId) {
        return entityStateService.getByEntityId(entityId)
                .filter(entity -> entity.getSource() == EntitySource.MANUAL)
                .orElseThrow(() -> new ResourceNotFoundException("Manual entity not found: " + entityId));
    }

    private EntityState reload(String entityId) {
        return entityStateService.getByEntityId(entityId)
                .orElseThrow(() -> new IllegalStateException("Manual entity could not be persisted: " + entityId));
    }

    private Map<String, Object> currentAttributes(EntityState entity) {
        return new LinkedHashMap<>(responseMapper.parseAttributes(entity.getAttributes()));
    }

    private Map<String, Object> attributesWithIcon(Map<String, Object> attributes, String icon) {
        if (icon != null && !icon.isBlank()) {
            attributes.put(ATTR_ICON, icon.trim());
        }
        return attributes;
    }

    /**
     * Normalisiert eine Zustandseingabe zu "on"/"off". Akzeptiert on/off, true/false, 1/0.
     *
     * @param fallback Rückgabewert bei leerer Eingabe; {@code null} erzwingt eine nicht-leere Eingabe
     */
    private String normalizeState(String rawState, String fallback) {
        if (rawState == null || rawState.isBlank()) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("State must not be empty");
        }
        return switch (rawState.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1" -> STATE_ON;
            case "off", "false", "0" -> STATE_OFF;
            default -> throw new IllegalArgumentException("Unsupported state '" + rawState + "' (expected on/off)");
        };
    }
}
