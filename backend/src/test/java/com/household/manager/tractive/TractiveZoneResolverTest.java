package com.household.manager.tractive;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TractiveZoneResolverTest {

    /** Haelt den Mock fest, damit Tests pruefen koennen, OB gelesen wurde. */
    private TractiveHomeSettingsService settingsService;

    private TractiveZoneResolver resolverWith(TractiveHomeSettings settings) {
        settingsService = mock(TractiveHomeSettingsService.class);
        lenient().when(settingsService.getSettings()).thenReturn(settings);
        return new TractiveZoneResolver(settingsService);
    }

    private TractiveZoneResolver resolverWithHome() {
        return resolverWith(new TractiveHomeSettings(48.2082, 16.3738, 100, 500, 60, 15, "Zuhause"));
    }

    private TractiveZoneResolver resolverWithoutHome() {
        return resolverWith(new TractiveHomeSettings(null, null, 100, 500, 60, 15, "Zuhause"));
    }

    @Test
    void positionInsideAZoneYieldsTheZoneName() {
        TractiveZoneResolver resolver = resolverWithoutHome();
        List<GeoZone> zones = List.of(new GeoZone("Garten", 48.2082, 16.3738, 100));

        assertEquals("Garten", resolver.resolve(48.2082, 16.3738, zones));

        // Bekannte Geofences: die Home-Zone wird gar nicht gebraucht, also auch nicht gelesen.
        verify(settingsService, never()).getSettings();
    }

    @Test
    void positionOutsideAllZonesYieldsAway() {
        TractiveZoneResolver resolver = resolverWithoutHome();
        List<GeoZone> zones = List.of(new GeoZone("Garten", 48.2082, 16.3738, 100));

        assertEquals("away", resolver.resolve(48.3000, 16.3738, zones));

        // Bekannte Geofences: die Home-Zone wird gar nicht gebraucht, also auch nicht gelesen.
        verify(settingsService, never()).getSettings();
    }

    @Test
    void homeZoneIsUsedWhenNoZonesAreKnown() {
        TractiveZoneResolver resolver = resolverWithHome();

        assertEquals("Zuhause", resolver.resolve(48.2082, 16.3738, List.of()));
        assertEquals("away", resolver.resolve(48.3000, 16.3738, List.of()));
    }

    @Test
    void withoutZonesAndWithoutHomeTheStateIsUnknown() {
        TractiveZoneResolver resolver = resolverWithoutHome();

        assertEquals("unknown", resolver.resolve(48.2082, 16.3738, List.of()));
    }
}
