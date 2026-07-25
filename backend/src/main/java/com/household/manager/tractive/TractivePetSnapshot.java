package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;

import java.util.List;

/**
 * Alles, was in einem Poll-Zyklus zu einem Haustier eingesammelt wurde.
 * {@code position} und {@code hardware} duerfen null sein.
 */
public record TractivePetSnapshot(
        TractiveTrackableDto trackable,
        TractivePositionDto position,
        TractiveHardwareDto hardware,
        List<GeoZone> zones
) {

    public String trackerId() {
        return trackable.deviceId();
    }

    public String name() {
        return trackable.displayName();
    }
}
