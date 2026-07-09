package com.household.manager.dto;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record FlowSummaryResponse(
        Long id, String name, String description, boolean enabled, boolean deployed,
        LocalDateTime deployedAt, LocalDateTime updatedAt) {
}
