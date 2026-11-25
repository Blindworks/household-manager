package com.household.manager.communicator;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunicatorSettingsServiceTest {

    private final ApplicationSettingsService store = mock(ApplicationSettingsService.class);
    private final AuditService audit = mock(AuditService.class);
    private final CommunicatorSettingsService service =
            new CommunicatorSettingsService(store, new CommunicatorProperties(), audit);

    @Test
    void ohneOderMitUngueltigerWahlGiltDieKonfiguration() {
        when(store.getSettingsByCategory("COMMUNICATOR")).thenReturn(Map.of(), Map.of("model", "quatsch"))
                .thenThrow(new IllegalStateException("db"));
        for (int i = 0; i < 3; i++) {
            assertThat(service.getModel()).isEqualTo("claude-sonnet-4-5");
        }
    }

    @Test
    void gespeicherteWahlGilt() {
        when(store.getSettingsByCategory("COMMUNICATOR")).thenReturn(Map.of("model", "claude-haiku-5-5"));
        assertThat(service.getModel()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void speichertNurBekannteModelle() {
        service.saveModel("claude-sonnet-5-5");
        verify(store).saveSettings("COMMUNICATOR", Map.of("model", "claude-sonnet-5-5"));
        verify(audit).record("communicator.model.update", "claude-sonnet-5-5");
        assertThatThrownBy(() -> service.saveModel("gpt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveModel(null)).isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).saveSettings("COMMUNICATOR", Map.of("model", "gpt"));
        verify(audit, never()).record(any(), org.mockito.ArgumentMatchers.eq("gpt"));
    }
}
