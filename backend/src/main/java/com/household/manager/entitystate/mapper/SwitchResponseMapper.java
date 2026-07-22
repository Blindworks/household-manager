package com.household.manager.entitystate.mapper;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Bildet eine schaltbare {@link EntityState} zusammen mit ihrem
 * {@link EntityUsage}-Zähler auf die API-{@link SwitchResponse} ab.
 */
@Component
@RequiredArgsConstructor
public class SwitchResponseMapper {

    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final String STATE_ON = "on";
    private static final String DEFAULT_ICON = "toggle_on";
    private static final String ATTR_ICON = "icon";
    /** Ältere Sensorwerte gelten als veraltet (Polling-Ausfall) und werden nicht angezeigt. */
    private static final Duration POWER_MAX_AGE = Duration.ofMinutes(5);

    private final EntityStateResponseMapper entityStateResponseMapper;

    /** @param usage darf null sein (Entität wurde noch nie geschaltet) */
    public SwitchResponse toResponse(EntityState entity, EntityUsage usage) {
        return toResponse(entity, usage, null);
    }

    /** @param powerSensor Power-Sensor gleicher Quelle; darf null sein */
    public SwitchResponse toResponse(EntityState entity, EntityUsage usage, EntityState powerSensor) {
        return SwitchResponse.builder()
                .entityId(entity.getEntityId())
                .domain(entity.getDomain().name())
                .source(entity.getSource().name())
                .displayName(entityStateResponseMapper.displayName(entity))
                .state(entity.getState())
                .available(!STATE_UNAVAILABLE.equals(entity.getState()))
                .icon(icon(entity))
                .confirmRequired(entity.isConfirmRequired())
                .powerWatts(powerWatts(entity, powerSensor))
                .toggleCount(usage != null ? usage.getToggleCount() : 0L)
                .lastToggledAt(usage != null ? usage.getLastToggledAt() : null)
                .build();
    }

    /** Leistung nur für eingeschaltete Schalter mit frischem, numerischem Sensorwert. */
    private Double powerWatts(EntityState entity, EntityState powerSensor) {
        if (powerSensor == null
                || !STATE_ON.equals(entity.getState())
                || STATE_UNAVAILABLE.equals(powerSensor.getState())
                || powerSensor.getLastUpdated() == null
                || powerSensor.getLastUpdated().isBefore(LocalDateTime.now().minus(POWER_MAX_AGE))) {
            return null;
        }
        try {
            return Double.parseDouble(powerSensor.getState());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String icon(EntityState entity) {
        Object icon = entityStateResponseMapper.parseAttributes(entity.getAttributes()).get(ATTR_ICON);
        return icon instanceof String text && !text.isBlank() ? text : DEFAULT_ICON;
    }
}
