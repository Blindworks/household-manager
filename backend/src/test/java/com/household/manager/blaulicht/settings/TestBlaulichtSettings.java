package com.household.manager.blaulicht.settings;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Einstellungsdienst für Tests, der die Defaults liefert. */
public final class TestBlaulichtSettings {

    private TestBlaulichtSettings() {
    }

    public static BlaulichtSettingsService defaults() {
        return returning(BlaulichtSettings.defaults());
    }

    public static BlaulichtSettingsService returning(BlaulichtSettings settings) {
        BlaulichtSettingsService service = mock(BlaulichtSettingsService.class);
        when(service.current()).thenReturn(settings);
        return service;
    }
}
