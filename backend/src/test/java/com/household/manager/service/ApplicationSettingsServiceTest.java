package com.household.manager.service;

import com.household.manager.model.entity.ApplicationSetting;
import com.household.manager.repository.ApplicationSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers only {@link ApplicationSettingsService#getString(String, String, String)}, since it
 * is the load-bearing contract behind the waste-collection settings seeding decision: three
 * keys are deliberately absent from the database, and that is only safe because a missing key
 * resolves to the caller's default rather than throwing or returning null.
 */
@ExtendWith(MockitoExtension.class)
class ApplicationSettingsServiceTest {

    private static final String CATEGORY = "SOME_CATEGORY";
    private static final String KEY = "some_key";
    private static final String DEFAULT_VALUE = "default";

    @Mock
    private ApplicationSettingRepository repository;

    private ApplicationSettingsService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationSettingsService(repository);
    }

    @Test
    void returnsDefaultWhenKeyIsAbsent() {
        when(repository.findByCategoryAndSettingKey(CATEGORY, KEY)).thenReturn(Optional.empty());

        assertThat(service.getString(CATEGORY, KEY, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    @Test
    void returnsDefaultWhenStoredValueIsEmpty() {
        when(repository.findByCategoryAndSettingKey(CATEGORY, KEY))
                .thenReturn(Optional.of(settingWithValue("")));

        assertThat(service.getString(CATEGORY, KEY, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    @Test
    void returnsDefaultWhenStoredValueIsWhitespaceOnly() {
        when(repository.findByCategoryAndSettingKey(CATEGORY, KEY))
                .thenReturn(Optional.of(settingWithValue("   ")));

        assertThat(service.getString(CATEGORY, KEY, DEFAULT_VALUE)).isEqualTo(DEFAULT_VALUE);
    }

    @Test
    void returnsStoredValueUnchangedWhenNonBlank() {
        when(repository.findByCategoryAndSettingKey(CATEGORY, KEY))
                .thenReturn(Optional.of(settingWithValue("https://x/cal.ics")));

        assertThat(service.getString(CATEGORY, KEY, DEFAULT_VALUE)).isEqualTo("https://x/cal.ics");
    }

    private ApplicationSetting settingWithValue(String value) {
        return ApplicationSetting.builder()
                .category(CATEGORY)
                .settingKey(KEY)
                .settingValue(value)
                .build();
    }
}
