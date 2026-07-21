package com.household.manager.nuki;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Steuerbare Schloss-Aktionen mit ihren Nuki-Web-API-Action-Codes. */
@Getter
@RequiredArgsConstructor
public enum NukiLockAction {
    UNLOCK(1),
    LOCK(2),
    /** Tür öffnen (Falle ziehen). */
    UNLATCH(3);

    private final int apiCode;
}
