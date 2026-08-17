package com.household.manager.push;

import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VapidKeyServiceTest {

    @Mock
    private ApplicationSettingsService settings;

    @Test
    void generatesAndPersistsKeyPairOnFirstAccess() {
        when(settings.getString(eq("PUSH_VAPID"), anyString(), isNull())).thenReturn(null);

        VapidKeyService.VapidKeys keys = new VapidKeyService(settings).keyPair();

        byte[] publicKey = Base64.getUrlDecoder().decode(keys.publicKey());
        assertEquals(65, publicKey.length);
        assertEquals(0x04, publicKey[0]);
        assertFalse(keys.privateKey().isBlank());
        verify(settings).saveSettings(eq("PUSH_VAPID"), argThat(map ->
                map.get("publicKey").equals(keys.publicKey())
                        && map.get("privateKey").equals(keys.privateKey())));
    }

    @Test
    void returnsStoredKeysWithoutRegenerating() {
        when(settings.getString("PUSH_VAPID", "publicKey", null)).thenReturn("pub");
        when(settings.getString("PUSH_VAPID", "privateKey", null)).thenReturn("priv");

        VapidKeyService.VapidKeys keys = new VapidKeyService(settings).keyPair();

        assertEquals("pub", keys.publicKey());
        assertEquals("priv", keys.privateKey());
        verify(settings, never()).saveSettings(anyString(), anyMap());
    }
}
