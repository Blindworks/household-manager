package com.household.manager.nuki.dto;

/** Schloss-Zustand für das Frontend (Dashboard-Kachel). */
public record NukiLockResponse(
        long smartlockId,
        String name,
        String state,
        String doorState,
        Integer batteryCharge,
        boolean batteryCritical
) {
}
