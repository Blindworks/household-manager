package com.household.manager.service;

import com.household.manager.dto.TasmotaElectricityPollingUpdateRequest;
import com.household.manager.model.entity.TasmotaPollingSettings;
import com.household.manager.repository.TasmotaPollingSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for persisting Tasmota polling settings.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TasmotaPollingSettingsService {

    public static final String ELECTRICITY_KEY = "ELECTRICITY";

    private final TasmotaPollingSettingsRepository repository;

    @Value("${tasmota.electricity.url}")
    private String defaultUrl;

    @Value("${tasmota.electricity.polling.interval-ms:60000}")
    private long defaultIntervalMs;

    @Value("${tasmota.electricity.polling.enabled:true}")
    private boolean defaultEnabled;

    @Transactional
    public TasmotaPollingSettings getOrCreateElectricitySettings() {
        return repository.findByPollingKey(ELECTRICITY_KEY)
                .orElseGet(this::createDefaultElectricitySettings);
    }

    @Transactional
    public TasmotaPollingSettings updateElectricitySettings(TasmotaElectricityPollingUpdateRequest request) {
        TasmotaPollingSettings settings = getOrCreateElectricitySettings();

        if (request.getEnabled() != null) {
            settings.setEnabled(request.getEnabled());
        }
        if (request.getIntervalMs() != null) {
            settings.setIntervalMs(request.getIntervalMs());
        }
        if (request.getUrl() != null && !request.getUrl().isBlank()) {
            settings.setUrl(request.getUrl().trim());
        }

        return repository.save(settings);
    }

    private TasmotaPollingSettings createDefaultElectricitySettings() {
        TasmotaPollingSettings settings = TasmotaPollingSettings.builder()
                .pollingKey(ELECTRICITY_KEY)
                .enabled(defaultEnabled)
                .intervalMs(defaultIntervalMs)
                .url(defaultUrl)
                .build();
        log.info("Creating default Tasmota polling settings for {}", ELECTRICITY_KEY);
        return repository.save(settings);
    }
}
