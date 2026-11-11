package com.household.manager.house;

import com.household.manager.model.entity.HouseOpening;
import com.household.manager.model.entity.HouseOpeningHinge;
import com.household.manager.model.entity.HouseOpeningKind;
import com.household.manager.model.entity.HouseOpeningSize;
import com.household.manager.model.entity.HouseOpeningSwing;

/** Request-/Response-Records der Fenster und Tueren im Digitalen Zwilling. */
public final class HouseOpeningDtos {

    private HouseOpeningDtos() {
    }

    public record Request(String floor, HouseOpeningKind kind, HouseOpeningSize size,
                          Double x1, Double y1, Double x2, Double y2,
                          HouseOpeningHinge hinge, HouseOpeningSwing swing, String deviceId) {
    }

    public record Response(Long id, String floor, HouseOpeningKind kind, HouseOpeningSize size,
                           double x1, double y1, double x2, double y2,
                           HouseOpeningHinge hinge, HouseOpeningSwing swing, String deviceId) {

        public static Response from(HouseOpening o) {
            return new Response(o.getId(), o.getFloor(), o.getKind(), o.getSize(),
                    o.getX1().doubleValue(), o.getY1().doubleValue(),
                    o.getX2().doubleValue(), o.getY2().doubleValue(),
                    o.getHinge(), o.getSwing(), o.getDeviceId());
        }
    }
}
