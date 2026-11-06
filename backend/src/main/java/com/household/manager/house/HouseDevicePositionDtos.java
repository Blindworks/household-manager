package com.household.manager.house;

import com.household.manager.model.entity.HouseDevicePosition;

/** Request-/Response-Records der Geraeteplaetze im Grundriss. */
public final class HouseDevicePositionDtos {

    private HouseDevicePositionDtos() {
    }

    public record Request(Double x, Double y) {
    }

    public record Response(String deviceId, double x, double y) {

        public static Response from(HouseDevicePosition position) {
            return new Response(position.getDeviceId(),
                    position.getPlanX().doubleValue(), position.getPlanY().doubleValue());
        }
    }
}
