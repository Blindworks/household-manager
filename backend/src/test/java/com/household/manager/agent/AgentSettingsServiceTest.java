package com.household.manager.agent;

import com.household.manager.audit.AuditService;
import com.household.manager.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSettingsServiceTest {
    @Mock ApplicationSettingsService settings;
    @Mock AuditService auditService;

    AgentSettingsService service() { return new AgentSettingsService(settings, auditService); }

    @Test void inactiveWhenValueMissing() {
        when(settings.getSettingsByCategory("AGENT")).thenReturn(Map.of());
        assertFalse(service().isActive());
    }
    @Test void activeWhenTrue() {
        when(settings.getSettingsByCategory("AGENT")).thenReturn(Map.of("agent_active", "true"));
        assertTrue(service().isActive());
    }
    @Test void inactiveWhenReadFails() {
        when(settings.getSettingsByCategory("AGENT")).thenThrow(new RuntimeException("db"));
        assertFalse(service().isActive());
    }
    @Test void setActiveSavesAndAudits() {
        service().setActive(true);
        verify(settings).saveSettings("AGENT", Map.of("agent_active", "true"));
        verify(auditService).record("agent.settings.update", "agent_active=true");
    }
}
