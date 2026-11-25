package com.household.manager.blaulicht;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Uhrzeiten und Ortslisten aus application.properties müssen sich binden lassen. */
class BlaulichtPropertiesBindingTest {

    @Test
    void bindetUhrzeitenUndOrtslisten() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "blaulicht.briefing.night-start", "20:00",
                "blaulicht.briefing.addendum-at", "16:30",
                "blaulicht.localities.districts", "Heilsberg,Massenheim")));

        BlaulichtProperties properties = binder.bind("blaulicht", BlaulichtProperties.class).get();

        assertThat(properties.getBriefing().getNightStart()).isEqualTo(LocalTime.of(20, 0));
        assertThat(properties.getBriefing().getAddendumAt()).isEqualTo(LocalTime.of(16, 30));
        assertThat(properties.getLocalities().getDistricts()).isEqualTo(List.of("Heilsberg", "Massenheim"));
    }
}
