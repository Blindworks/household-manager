package com.household.manager.kasa.dto;

public record KasaStatusDto(
        boolean relayState,
        String alias,
        String deviceId
) {
}
