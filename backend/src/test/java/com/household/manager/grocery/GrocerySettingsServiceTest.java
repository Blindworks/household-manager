package com.household.manager.grocery;

import com.household.manager.grocery.dto.GrocerySettingsDto;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrocerySettingsServiceTest {

    private final Map<String, String> store = new HashMap<>();
    private GrocerySettingsService service;

    @BeforeEach
    void setUp() {
        ApplicationSettingsService backing = mock(ApplicationSettingsService.class);
        when(backing.getString(anyString(), anyString(), anyString())).thenAnswer(i ->
                store.getOrDefault(i.<String>getArgument(1), i.<String>getArgument(2)));
        when(backing.getBoolean(anyString(), anyString(), anyBoolean())).thenAnswer(i ->
                store.containsKey(i.<String>getArgument(1))
                        ? Boolean.parseBoolean(store.get(i.<String>getArgument(1)))
                        : i.<Boolean>getArgument(2));
        doAnswer(i -> store.put(i.getArgument(1), i.getArgument(2)))
                .when(backing).saveSetting(anyString(), anyString(), anyString());
        service = new GrocerySettingsService(backing);
    }

    @Test
    void defaultsApplyWithoutStoredValues() {
        assertThat(service.zip()).isEqualTo("61118");
        assertThat(service.lat()).isEqualTo(50.1781);
        assertThat(service.dealThresholdPercent()).isEqualTo(10);
    }

    @Test
    void thresholdOutOfRangeFallsBackToDefault() {
        for (String bad : new String[]{"0", "abc", "51", "-3"}) {
            store.put("deal_threshold_percent", bad);
            assertThat(service.dealThresholdPercent()).as(bad).isEqualTo(10);
        }
        store.put("deal_threshold_percent", "25");
        assertThat(service.dealThresholdPercent()).isEqualTo(25);
    }

    @Test
    void accountIdIsCreatedOnceAndReused() {
        String first = service.kaufdaAccountId();
        assertThat(first).isNotBlank();
        assertThat(service.kaufdaAccountId()).isEqualTo(first);
        assertThat(store.get("kaufda_account_id")).isEqualTo(first);
    }

    @Test
    void updateValidatesAndStores() {
        service.update(new GrocerySettingsDto("60311", 50.1, 8.6, 15));
        assertThat(service.zip()).isEqualTo("60311");
        assertThat(service.dealThresholdPercent()).isEqualTo(15);
    }

    @Test
    void updateRejectsMissingFields() {
        assertThatThrownBy(() -> service.update(new GrocerySettingsDto("60311", null, 8.0, 10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(new GrocerySettingsDto("60311", 50.0, 8.0, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateRejectsInvalidValues() {
        assertThatThrownBy(() -> service.update(new GrocerySettingsDto("6031", 50.0, 8.0, 10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(new GrocerySettingsDto("60311", Double.NaN, 8.0, 10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(new GrocerySettingsDto("60311", 50.0, 181.0, 10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(new GrocerySettingsDto("60311", 50.0, 8.0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
