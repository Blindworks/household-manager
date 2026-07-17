package com.household.manager.entitystate;

import com.household.manager.entitystate.HouseModes.HouseModeDefinition;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Legt die Haus-Modi aus {@link HouseModes#CATALOG} beim Start an (idempotent).
 * Vorhandene Entities behalten Zustand, Namen und Attribute; fehlt nur das
 * Marker-Attribut (z. B. früher manuell angelegter Helfer gleichen Namens),
 * wird es ergänzt. Fehler eines Modus verhindern das Seeding der übrigen nicht.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HouseModeInitializer {

    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void seedHouseModes() {
        for (HouseModeDefinition definition : HouseModes.CATALOG) {
            try {
                seed(definition);
            } catch (Exception ex) {
                log.warn("Haus-Modus {} konnte nicht angelegt werden: {}", definition.name(), ex.getMessage());
            }
        }
    }

    private void seed(HouseModeDefinition definition) {
        String entityId = HouseModes.entityId(definition);
        EntityState existing = entityStateService.getByEntityId(entityId).orElse(null);
        if (existing == null) {
            report(entityId, EntityIds.slug(definition.name()), definition.name(),
                    ManualEntityService.STATE_OFF, newModeAttributes(definition.icon()));
            log.info("Haus-Modus angelegt: {}", entityId);
            return;
        }
        Map<String, Object> attributes =
                new LinkedHashMap<>(responseMapper.parseAttributes(existing.getAttributes()));
        if (HouseModes.isMode(attributes)) {
            return;
        }
        attributes.put(HouseModes.ATTR_MODE, true);
        report(entityId, existing.getSourceRef(), existing.getFriendlyName(), existing.getState(), attributes);
        log.info("Haus-Modus-Marker ergänzt: {}", entityId);
    }

    private Map<String, Object> newModeAttributes(String icon) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("icon", icon);
        attributes.put(HouseModes.ATTR_MODE, true);
        return attributes;
    }

    private void report(String entityId, String sourceRef, String friendlyName, String state,
                        Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(entityId)
                .domain(EntityDomain.INPUT_BOOLEAN)
                .source(EntitySource.MANUAL)
                .sourceRef(sourceRef)
                .friendlyName(friendlyName)
                .state(state)
                .attributes(attributes)
                .build());
    }
}
