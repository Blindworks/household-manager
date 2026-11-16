package com.household.manager.grocery.dto;

import com.household.manager.grocery.source.SourceKey;

import java.time.LocalDateTime;

/** Status einer Quelle; ohne bisherigen Lauf sind {@code lastRunAt}, {@code offerCount} und {@code ok} null. */
public record SourceStatusDto(SourceKey source, boolean enabled, LocalDateTime lastRunAt,
                              Integer offerCount, Boolean ok, String reason) {
}
