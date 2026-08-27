package com.household.manager.blink;

import com.household.manager.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.Mockito.*;

class BlinkCameraServiceTest {

    private final BlinkSidecarClient client = mock(BlinkSidecarClient.class);
    private final BlinkPollingService pollingService = mock(BlinkPollingService.class);
    private final AuditService auditService = mock(AuditService.class);
    private BlinkCameraService service;

    @BeforeEach
    void setUp() {
        service = new BlinkCameraService(client, pollingService, auditService);
    }

    @Test
    void kameraScharfSchaltenAuditiertUndPolltNach() {
        service.setCameraArmed("123", true);

        InOrder order = inOrder(client, auditService, pollingService);
        order.verify(client).setCameraArmed("123", true);
        order.verify(auditService).record("blink.camera.arm", "123");
        order.verify(pollingService).pollForced();
    }

    @Test
    void kameraUnscharfSchaltenAuditiertDisarm() {
        service.setCameraArmed("123", false);
        verify(auditService).record("blink.camera.disarm", "123");
    }

    @Test
    void systemSchaltenAuditiertMitSyncName() {
        service.setSystemArmed("Zuhause", false);

        InOrder order = inOrder(client, auditService, pollingService);
        order.verify(client).setSyncArmed("Zuhause", false);
        order.verify(auditService).record("blink.system.disarm", "Zuhause");
        order.verify(pollingService).pollForced();
    }

    @Test
    void schnappschussLaeuftOhneAudit() {
        when(client.snapshot("123")).thenReturn(new byte[]{1});
        service.snapshot("123");
        verifyNoInteractions(auditService);
        verify(pollingService, never()).pollForced();
    }
}
