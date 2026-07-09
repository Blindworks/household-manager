package com.household.manager.dto;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record FlowDetailResponse(
        Long id, String name, String description, boolean enabled, boolean deployed,
        String draftDefinition, String deployedDefinition,
        LocalDateTime deployedAt, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
