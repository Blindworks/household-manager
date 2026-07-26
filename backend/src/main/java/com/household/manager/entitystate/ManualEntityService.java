package com.household.manager.entitystate;

import com.household.manager.audit.AuditService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Verwaltet vom Benutzer angelegte Entitäten (Helfer) verschiedener Typen:
 * schaltbare Modi (INPUT_BOOLEAN), Zahlen (INPUT_NUMBER), Freitext (INPUT_TEXT)
 * und Auswahlwerte (INPUT_SELECT) – z. B. "Nachtmodus", "Zieltemperatur" oder "Urlaubsstatus".
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
    private static final String ATTR_OPTIONS = "options";
    private static final String ATTR_MIN = "min";
    private static final String ATTR_MAX = "max";
    private static final String ATTR_STEP = "step";
    private static final String ATTR_UNIT = "unit";

    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;
    private final AuditService auditService;

    /**
     * Legt eine neue manuelle Entität gemäß Definition an. Die Entity-ID wird aus
     * Typ und Namen abgeleitet und bleibt bei späterem Umbenennen stabil.
     *
     * @throws DuplicateEntityException  wenn Typ + Name auf eine existierende ID abbilden
     * @throws IllegalArgumentException  bei ungültiger/fehlender Konfiguration
     */
    public EntityState create(ManualEntityDefinition definition) {
        EntityDomain domain = definition.domain() != null ? definition.domain() : EntityDomain.INPUT_BOOLEAN;
        if (!domain.isManualHelper()) {
            throw new IllegalArgumentException("Unsupported manual entity type: " + domain);
        }
        if (definition.name() == null || definition.name().isBlank()) {
            throw new IllegalArgumentException("Name must not be empty");
        }

        String friendlyName = definition.name().trim();
        String entityId = EntityIds.build(domain, EntitySource.MANUAL, friendlyName, null);
        if (entityStateService.getByEntityId(entityId).isPresent()) {
            throw new DuplicateEntityException("Entity already exists: " + entityId);
        }

        Map<String, Object> attributes = buildAttributes(domain, definition);
        String state = initialState(domain, definition.state(), attributes);
        report(entityId, EntityIds.slug(friendlyName), friendlyName, domain, state, attributes);

        return reload(entityId);
    }

    /** Setzt den Wert einer bestehenden manuellen Entität (typabhängig validiert). */
    public EntityState setState(String entityId, String rawState) {
        EntityState entity = requireManual(entityId);
        Map<String, Object> attributes = currentAttributes(entity);
        String state = validateValue(entity.getDomain(), rawState, attributes);
        report(entity, state);
        return reload(entityId);
    }

    /** Schaltet einen Boolean-Helfer um (on ↔ off). Nur für INPUT_BOOLEAN gültig. */
    public EntityState toggle(String entityId) {
        EntityState entity = requireManual(entityId);
        if (entity.getDomain() != EntityDomain.INPUT_BOOLEAN) {
            throw new IllegalArgumentException("Toggle is only supported for input_boolean entities");
        }
        report(entity, STATE_ON.equals(entity.getState()) ? STATE_OFF : STATE_ON);
        auditService.record("entity.toggle", entityId);
        return reload(entityId);
    }

    /** Benennt eine manuelle Entität um; die Entity-ID und Konfiguration bleiben erhalten. */
    public EntityState rename(String entityId, String newName, String icon) {
        EntityState entity = requireManual(entityId);
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("Name must not be empty");
        }
        Map<String, Object> attributes = withIcon(currentAttributes(entity), icon);
        report(entity.getEntityId(), entity.getSourceRef(), newName.trim(), entity.getDomain(),
                entity.getState(), attributes);
        return reload(entityId);
    }

    /** Löscht eine manuelle Entität. */
    public void delete(String entityId) {
        requireManual(entityId);
        entityStateService.deleteByEntityId(entityId);
    }

    // --- Attribut- und Zustandslogik -------------------------------------------------

    private Map<String, Object> buildAttributes(EntityDomain domain, ManualEntityDefinition def) {
        Map<String, Object> attributes = withIcon(new LinkedHashMap<>(), def.icon());
        switch (domain) {
            case INPUT_NUMBER -> {
                putIfPresent(attributes, ATTR_MIN, def.min());
                putIfPresent(attributes, ATTR_MAX, def.max());
                putIfPresent(attributes, ATTR_STEP, def.step());
                if (def.unit() != null && !def.unit().isBlank()) {
                    attributes.put(ATTR_UNIT, def.unit().trim());
                }
                if (def.min() != null && def.max() != null && def.min() > def.max()) {
                    throw new IllegalArgumentException("min must not be greater than max");
                }
            }
            case INPUT_SELECT -> attributes.put(ATTR_OPTIONS, sanitizeOptions(def.options()));
            default -> { /* boolean/text benötigen keine Zusatzkonfiguration */ }
        }
        return attributes;
    }

    private String initialState(EntityDomain domain, String rawState, Map<String, Object> attributes) {
        boolean blank = rawState == null || rawState.isBlank();
        return switch (domain) {
            case INPUT_BOOLEAN -> blank ? STATE_OFF : normalizeBoolean(rawState);
            case INPUT_NUMBER -> blank ? defaultNumber(attributes) : normalizeNumber(rawState, attributes);
            case INPUT_TEXT -> blank ? "" : rawState.trim();
            case INPUT_SELECT -> blank ? firstOption(attributes) : normalizeSelect(rawState, attributes);
            default -> throw new IllegalArgumentException("Unsupported manual entity type: " + domain);
        };
    }

    private String validateValue(EntityDomain domain, String rawState, Map<String, Object> attributes) {
        if (rawState == null || rawState.isBlank()) {
            if (domain == EntityDomain.INPUT_TEXT) {
                return "";
            }
            throw new IllegalArgumentException("State must not be empty");
        }
        return switch (domain) {
            case INPUT_BOOLEAN -> normalizeBoolean(rawState);
            case INPUT_NUMBER -> normalizeNumber(rawState, attributes);
            case INPUT_TEXT -> rawState.trim();
            case INPUT_SELECT -> normalizeSelect(rawState, attributes);
            default -> throw new IllegalArgumentException("Unsupported manual entity type: " + domain);
        };
    }

    private String normalizeBoolean(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1" -> STATE_ON;
            case "off", "false", "0" -> STATE_OFF;
            default -> throw new IllegalArgumentException("Unsupported state '" + raw + "' (expected on/off)");
        };
    }

    private String normalizeNumber(String raw, Map<String, Object> attributes) {
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("State '" + raw + "' is not a valid number");
        }
        Double min = numberAttr(attributes, ATTR_MIN);
        Double max = numberAttr(attributes, ATTR_MAX);
        if (min != null && value < min) {
            throw new IllegalArgumentException("Value " + value + " is below minimum " + min);
        }
        if (max != null && value > max) {
            throw new IllegalArgumentException("Value " + value + " is above maximum " + max);
        }
        return formatNumber(value);
    }

    private String normalizeSelect(String raw, Map<String, Object> attributes) {
        String candidate = raw.trim();
        List<String> options = optionsFrom(attributes);
        if (!options.contains(candidate)) {
            throw new IllegalArgumentException("Value '" + candidate + "' is not one of the options " + options);
        }
        return candidate;
    }

    private String defaultNumber(Map<String, Object> attributes) {
        Double min = numberAttr(attributes, ATTR_MIN);
        return formatNumber(min != null ? min : 0d);
    }

    private String firstOption(Map<String, Object> attributes) {
        List<String> options = optionsFrom(attributes);
        return options.get(0);
    }

    private List<String> sanitizeOptions(List<String> options) {
        List<String> cleaned = new ArrayList<>();
        if (options != null) {
            for (String option : options) {
                if (option == null) {
                    continue;
                }
                String trimmed = option.trim();
                if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
                    cleaned.add(trimmed);
                }
            }
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Select entity requires at least one option");
        }
        return cleaned;
    }

    private String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> optionsFrom(Map<String, Object> attributes) {
        Object raw = attributes.get(ATTR_OPTIONS);
        if (raw instanceof List<?> list) {
            List<String> options = new ArrayList<>();
            for (Object item : (List<Object>) list) {
                options.add(String.valueOf(item));
            }
            return options;
        }
        return List.of();
    }

    private Double numberAttr(Map<String, Object> attributes, String key) {
        Object raw = attributes.get(key);
        return raw instanceof Number number ? number.doubleValue() : null;
    }

    private Map<String, Object> withIcon(Map<String, Object> attributes, String icon) {
        if (icon != null && !icon.isBlank()) {
            attributes.put(ATTR_ICON, icon.trim());
        }
        return attributes;
    }

    private void putIfPresent(Map<String, Object> attributes, String key, Double value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    // --- Persistenz-Hilfen -----------------------------------------------------------

    private void report(EntityState entity, String newState) {
        report(entity.getEntityId(), entity.getSourceRef(), entity.getFriendlyName(),
                entity.getDomain(), newState, currentAttributes(entity));
    }

    private void report(String entityId, String sourceRef, String friendlyName, EntityDomain domain,
                        String state, Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(entityId)
                .domain(domain)
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
}
