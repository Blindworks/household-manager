package com.household.manager.dirigera;

import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirigeraSettingsServiceTest {

    private final ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
    private final DirigeraSettingsService service = new DirigeraSettingsService(settings);

    @Test
    void getWirftNieUndLiefertLeer() {
        when(settings.getSettingsByCategory("DIRIGERA")).thenThrow(new RuntimeException("db weg"));

        DirigeraHubSettings result = service.get();

        assertThat(result.host()).isNull();
        assertThat(result.accessToken()).isNull();
        assertThat(result.isPaired()).isFalse();
    }

    @Test
    void leereStringsSindNull() {
        when(settings.getSettingsByCategory("DIRIGERA")).thenReturn(Map.of(
                "hub_host", "", "access_token", "  ", "cert_sha256", "", "hub_id", ""));

        DirigeraHubSettings result = service.get();

        assertThat(result.host()).isNull();
        assertThat(result.accessToken()).isNull();
        assertThat(result.certSha256()).isNull();
        assertThat(result.hubId()).isNull();
    }

    @Test
    void isPairedBrauchtAlleVier() {
        assertThat(new DirigeraHubSettings("h", "t", "c", "i").isPaired()).isTrue();
        assertThat(new DirigeraHubSettings(null, "t", "c", "i").isPaired()).isFalse();
        assertThat(new DirigeraHubSettings("h", null, "c", "i").isPaired()).isFalse();
        assertThat(new DirigeraHubSettings("h", "t", null, "i").isPaired()).isFalse();
        assertThat(new DirigeraHubSettings("h", "t", "c", null).isPaired()).isFalse();
    }

    @Test
    void getLiestAlleWerte() {
        when(settings.getSettingsByCategory("DIRIGERA")).thenReturn(Map.of(
                "hub_host", "192.168.1.50", "access_token", "tok", "cert_sha256", "ab", "hub_id", "id1"));

        assertThat(service.get()).isEqualTo(new DirigeraHubSettings("192.168.1.50", "tok", "ab", "id1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveHostBehaeltPairing() {
        service.saveHost("192.168.1.60");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(settings).saveSettings(eq("DIRIGERA"), captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys("hub_host").containsEntry("hub_host", "192.168.1.60");
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearPairingSchreibtLeereStringsAberNichtHost() {
        service.clearPairing();

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(settings).saveSettings(eq("DIRIGERA"), captor.capture());
        assertThat(captor.getValue())
                .containsOnlyKeys("access_token", "cert_sha256", "hub_id")
                .containsEntry("access_token", "")
                .containsEntry("cert_sha256", "")
                .containsEntry("hub_id", "");
    }

    @Test
    @SuppressWarnings("unchecked")
    void savePairingSchreibtDreiWerte() {
        service.savePairing("tok", "ab", "id1");

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(settings).saveSettings(eq("DIRIGERA"), captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys("access_token", "cert_sha256", "hub_id")
                .containsEntry("access_token", "tok");
    }
}
