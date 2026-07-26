package com.household.manager.tractive;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TractiveZoneResolverTest {

    private TractiveProperties propertiesWithHome() {
        TractiveProperties properties = new TractiveProperties();
        properties.setHomeLatitude(48.2082);
        properties.setHomeLongitude(16.3738);
        properties.setHomeRadiusMeters(100);
        properties.setHomeZoneName("Zuhause");
        return properties;
    }

    @Test
    void positionInsideAZoneYieldsTheZoneName() {
        TractiveZoneResolver resolver = new TractiveZoneResolver(new TractiveProperties());
        List<GeoZone> zones = List.of(new GeoZone("Garten", 48.2082, 16.3738, 100));

        assertEquals("Garten", resolver.resolve(48.2082, 16.3738, zones));
    }

    @Test
    void positionOutsideAllZonesYieldsAway() {
        TractiveZoneResolver resolver = new TractiveZoneResolver(new TractiveProperties());
        List<GeoZone> zones = List.of(new GeoZone("Garten", 48.2082, 16.3738, 100));

        assertEquals("away", resolver.resolve(48.3000, 16.3738, zones));
    }

    @Test
    void homeZoneIsUsedWhenNoZonesAreKnown() {
        TractiveZoneResolver resolver = new TractiveZoneResolver(propertiesWithHome());

        assertEquals("Zuhause", resolver.resolve(48.2082, 16.3738, List.of()));
        assertEquals("away", resolver.resolve(48.3000, 16.3738, List.of()));
    }

    @Test
    void withoutZonesAndWithoutHomeTheStateIsUnknown() {
        TractiveZoneResolver resolver = new TractiveZoneResolver(new TractiveProperties());

        assertEquals("unknown", resolver.resolve(48.2082, 16.3738, List.of()));
    }
}
