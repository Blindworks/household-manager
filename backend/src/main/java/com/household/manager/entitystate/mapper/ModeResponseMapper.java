package com.household.manager.entitystate.mapper;

import com.household.manager.dto.ModeResponse;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Bildet eine Modus-Entity auf die API-{@link ModeResponse} ab. */
@Component
@RequiredArgsConstructor
public class ModeResponseMapper {

    private static final String DEFAULT_ICON = "flag";
    private static final String ATTR_ICON = "icon";

    private final EntityStateResponseMapper entityStateResponseMapper;

    public ModeResponse toResponse(EntityState entity) {
        Object icon = entityStateResponseMapper.parseAttributes(entity.getAttributes()).get(ATTR_ICON);
        return ModeResponse.builder()
                .entityId(entity.getEntityId())
                .displayName(entityStateResponseMapper.displayName(entity))
                .icon(icon instanceof String text && !text.isBlank() ? text : DEFAULT_ICON)
                .state(entity.getState())
                .build();
    }
}
