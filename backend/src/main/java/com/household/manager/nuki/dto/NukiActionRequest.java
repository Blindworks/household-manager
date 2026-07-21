package com.household.manager.nuki.dto;

import com.household.manager.nuki.NukiLockAction;
import jakarta.validation.constraints.NotNull;

/** Aktionsanforderung an ein Schloss ({@code {"action": "LOCK"}}). */
public record NukiActionRequest(@NotNull NukiLockAction action) {
}
