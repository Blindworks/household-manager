package com.household.manager.system;

import com.household.manager.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SystemRebootServiceTest {

    @Mock
    private RebooterClient rebooterClient;
    @Mock
    private AuditService auditService;

    private RebooterProperties properties;
    private SystemRebootService service;

    @BeforeEach
    void setUp() {
        properties = new RebooterProperties();
        properties.setBaseUrl("http://rebooter:8095");
        properties.setToken("geheim");
        service = new SystemRebootService(properties, rebooterClient, auditService);
    }

    @Test
    void wirft_ohne_konfigurierte_url() {
        properties.setBaseUrl("");

        assertThatThrownBy(() -> service.reboot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nicht konfiguriert");
        verify(rebooterClient, never()).triggerReboot();
        verify(auditService, never()).record(anyString(), anyString());
    }

    @Test
    void wirft_ohne_konfigurierten_token() {
        properties.setToken(" ");

        assertThatThrownBy(() -> service.reboot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nicht konfiguriert");
        verify(rebooterClient, never()).triggerReboot();
    }

    @Test
    void schreibt_audit_vor_dem_sidecar_aufruf() {
        service.reboot();

        InOrder order = inOrder(auditService, rebooterClient);
        order.verify(auditService).record("system.reboot", "alle Container");
        order.verify(rebooterClient).triggerReboot();
    }

    @Test
    void reicht_sidecar_fehler_durch_audit_bleibt() {
        doThrow(new RebooterException("Sidecar nicht erreichbar")).when(rebooterClient).triggerReboot();

        assertThatThrownBy(() -> service.reboot()).isInstanceOf(RebooterException.class);
        verify(auditService).record("system.reboot", "alle Container");
    }
}
