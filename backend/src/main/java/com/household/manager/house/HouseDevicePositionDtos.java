package com.household.manager.house;

import com.household.manager.model.entity.HouseDevicePosition;

/** Request-/Response-Records der Geraeteplaetze im Grundriss. */
public final class HouseDevicePositionDtos {

    private HouseDevicePositionDtos() {
    }

    public record Request(Double x, Double y, Integer rotation) {
    }

    public record Response(String deviceId, double x, double y, Integer rotation) {

        public static Response from(HouseDevicePosition position) {
            return new Response(position.getDeviceId(),
                    position.getPlanX().doubleValue(), position.getPlanY().doubleValue(), position.getRotationDeg());
        }
    }
}
