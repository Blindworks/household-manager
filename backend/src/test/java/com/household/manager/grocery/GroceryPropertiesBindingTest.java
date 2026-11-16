package com.household.manager.grocery;

import com.household.manager.grocery.source.SourceKey;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroceryPropertiesBindingTest {

    @Test
    void sourceSwitchFromPropertiesTurnsOnlyThatSourceOff() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("grocery.sources.REWE", "false")));

        GroceryProperties properties = binder.bindOrCreate("grocery", Bindable.of(GroceryProperties.class));

        assertThat(properties.isSourceEnabled(SourceKey.REWE)).isFalse();
        assertThat(properties.isSourceEnabled(SourceKey.MARKTGURU)).isTrue();
        assertThat(properties.isSourceEnabled(SourceKey.PENNY)).isTrue();
    }
}
