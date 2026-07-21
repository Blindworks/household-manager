package com.household.manager.nuki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Zustandsobjekt eines Smartlocks aus der Nuki Web API.
 * Codes: state 0=uncalibrated 1=locked 2=unlocking 3=unlocked 4=locking
 * 5=unlatched 6=unlocked(lock'n'go) 7=unlatching 254=motor blocked;
 * doorState 0=unavailable 1=deactivated 2=closed 3=open 4=unknown 5=calibrating.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NukiSmartlockStateDto(
        Integer state,
        Integer doorState,
        Boolean batteryCritical,
        Integer batteryCharge
) {
}
