package com.household.manager.nuki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Ein Smartlock aus {@code GET /smartlock} der Nuki Web API. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NukiSmartlockDto(
        long smartlockId,
        String name,
        NukiSmartlockStateDto state
) {
}
