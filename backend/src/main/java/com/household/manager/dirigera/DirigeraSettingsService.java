package com.household.manager.dirigera;

import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Hub-Einstellungen in {@code application_settings}. Die Spalte ist NOT NULL, ein leerer
 * String bedeutet deshalb "nicht gesetzt". Lesen wirft nie.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirigeraSettingsService {

    static final String CATEGORY = "DIRIGERA";
    static final String KEY_HOST = "hub_host";
    static final String KEY_TOKEN = "access_token";
    static final String KEY_CERT = "cert_sha256";
    static final String KEY_HUB_ID = "hub_id";

    private final ApplicationSettingsService applicationSettings;

    public DirigeraHubSettings get() {
        try {
            Map<String, String> values = applicationSettings.getSettingsByCategory(CATEGORY);
            return new DirigeraHubSettings(
                    blankToNull(values.get(KEY_HOST)),
                    blankToNull(values.get(KEY_TOKEN)),
                    blankToNull(values.get(KEY_CERT)),
                    blankToNull(values.get(KEY_HUB_ID)));
        } catch (RuntimeException e) {
            log.warn("Dirigera-Einstellungen nicht lesbar: {}", e.getMessage());
            return new DirigeraHubSettings(null, null, null, null);
        }
    }

    /** Nur der Host; Token, Zertifikat und hubId bleiben, saveSettings upsertet je Schluessel. */
    public void saveHost(String host) {
        applicationSettings.saveSettings(CATEGORY, Map.of(KEY_HOST, host));
    }

    public void savePairing(String accessToken, String certSha256, String hubId) {
        applicationSettings.saveSettings(CATEGORY, Map.of(
                KEY_TOKEN, accessToken, KEY_CERT, certSha256, KEY_HUB_ID, hubId));
    }

    /** Entkoppelt, behaelt aber den Host. */
    public void clearPairing() {
        applicationSettings.saveSettings(CATEGORY, Map.of(KEY_TOKEN, "", KEY_CERT, "", KEY_HUB_ID, ""));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
