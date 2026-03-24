package com.household.manager.service;

import com.household.manager.model.entity.ApplicationSetting;
import com.household.manager.repository.ApplicationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic service for reading and writing application settings from the database.
 * Settings are organized by category and stored as string key-value pairs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationSettingsService {

    private final ApplicationSettingRepository repository;

    /**
     * Returns all settings for a given category as a key-value map.
     */
    @Transactional(readOnly = true)
    public Map<String, String> getSettingsByCategory(String category) {
        return repository.findByCategory(category).stream()
                .collect(Collectors.toMap(
                        ApplicationSetting::getSettingKey,
                        ApplicationSetting::getSettingValue));
    }

    /**
     * Saves (upserts) a single setting.
     */
    @Transactional
    public void saveSetting(String category, String key, String value) {
        ApplicationSetting setting = repository
                .findByCategoryAndSettingKey(category, key)
                .orElseGet(() -> ApplicationSetting.builder()
                        .category(category)
                        .settingKey(key)
                        .build());
        setting.setSettingValue(value);
        repository.save(setting);
    }

    /**
     * Saves (upserts) multiple settings for a category in a single transaction.
     */
    @Transactional
    public void saveSettings(String category, Map<String, String> settings) {
        settings.forEach((key, value) -> saveSetting(category, key, value));
        log.info("Saved {} settings for category '{}'", settings.size(), category);
    }

    /**
     * Returns a boolean setting with a default fallback.
     */
    @Transactional(readOnly = true)
    public boolean getBoolean(String category, String key, boolean defaultValue) {
        return repository.findByCategoryAndSettingKey(category, key)
                .map(s -> Boolean.parseBoolean(s.getSettingValue()))
                .orElse(defaultValue);
    }

    /**
     * Returns an integer setting with a default fallback.
     */
    @Transactional(readOnly = true)
    public int getInt(String category, String key, int defaultValue) {
        return repository.findByCategoryAndSettingKey(category, key)
                .map(s -> Integer.parseInt(s.getSettingValue()))
                .orElse(defaultValue);
    }

    /**
     * Returns a long setting with a default fallback.
     */
    @Transactional(readOnly = true)
    public long getLong(String category, String key, long defaultValue) {
        return repository.findByCategoryAndSettingKey(category, key)
                .map(s -> Long.parseLong(s.getSettingValue()))
                .orElse(defaultValue);
    }
}
