package com.household.manager.entitystate.mapper;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.model.entity.EntityState;
import com.household.manager.model.entity.EntityUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Bildet eine schaltbare {@link EntityState} zusammen mit ihrem
 * {@link EntityUsage}-Zähler auf die API-{@link SwitchResponse} ab.
 */
@Component
@RequiredArgsConstructor
public class SwitchResponseMapper {

    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final String DEFAULT_ICON = "toggle_on";
    private static final String ATTR_ICON = "icon";

    private final EntityStateResponseMapper entityStateResponseMapper;

    /** @param usage darf null sein (Entität wurde noch nie geschaltet) */
    public SwitchResponse toResponse(EntityState entity, EntityUsage usage) {
        return SwitchResponse.builder()
                .entityId(entity.getEntityId())
                .domain(entity.getDomain().name())
                .source(entity.getSource().name())
                .displayName(entityStateResponseMapper.displayName(entity))
                .state(entity.getState())
                .available(!STATE_UNAVAILABLE.equals(entity.getState()))
                .icon(icon(entity))
                .toggleCount(usage != null ? usage.getToggleCount() : 0L)
                .lastToggledAt(usage != null ? usage.getLastToggledAt() : null)
                .build();
    }

    private String icon(EntityState entity) {
        Object icon = entityStateResponseMapper.parseAttributes(entity.getAttributes()).get(ATTR_ICON);
        return icon instanceof String text && !text.isBlank() ? text : DEFAULT_ICON;
    }
}
