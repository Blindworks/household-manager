package com.household.manager.entitystate.mapper;

import com.household.manager.dto.ModeResponse;
import com.household.manager.mode.ModeQuickAccessResolver;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Bildet eine Modus-Entity auf die API-{@link ModeResponse} ab. */
@Component
@RequiredArgsConstructor
public class ModeResponseMapper {

    private static final String DEFAULT_ICON = "flag";
    private static final String ATTR_ICON = "icon";

    private final EntityStateResponseMapper entityStateResponseMapper;
    private final ModeQuickAccessResolver quickAccessResolver;

    /** Einzelabbildung; fragt die offenen Zeitfenster selbst ab. */
    public ModeResponse toResponse(EntityState entity) {
        return toResponse(entity, quickAccessResolver.dueEntityIds());
    }

    /**
     * Abbildung mit bereits geladenen Fenstern. Ein Listenabruf reicht sie durch, damit die
     * Fenster einmal je Antwort geladen werden und nicht einmal je Modus.
     */
    public ModeResponse toResponse(EntityState entity, Set<String> dueEntityIds) {
        Object icon = entityStateResponseMapper.parseAttributes(entity.getAttributes()).get(ATTR_ICON);
        return ModeResponse.builder()
                .entityId(entity.getEntityId())
                .displayName(entityStateResponseMapper.displayName(entity))
                .icon(icon instanceof String text && !text.isBlank() ? text : DEFAULT_ICON)
                .state(entity.getState())
                .quickAccess(dueEntityIds.contains(entity.getEntityId()))
                .build();
    }
}
