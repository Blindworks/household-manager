package com.household.manager.blink;

import com.household.manager.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BlinkCameraServiceTest {

    private final BlinkSidecarClient client = mock(BlinkSidecarClient.class);
    private final BlinkPollingService pollingService = mock(BlinkPollingService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BlinkMotionService motionService = mock(BlinkMotionService.class);
    private BlinkCameraService service;

    @BeforeEach
    void setUp() {
        service = new BlinkCameraService(client, pollingService, auditService, motionService);
    }

    @Test
    void kameralisteWirdUmLetzteBewegungAngereichert() {
        when(client.listCameras(false)).thenReturn(List.of(new BlinkSidecarClient.SidecarCamera(
                "123", "Frontdoor", "doorbell", true, "ok", "Zuhause", true)));
        when(motionService.lastMotion("123")).thenReturn(Optional.of(
                new BlinkMotionService.LastMotion("2026-08-27T12:00:00", "42")));

        List<BlinkCameraService.CameraResponse> cameras = service.listCameras();

        assertThat(cameras).hasSize(1);
        assertThat(cameras.get(0).lastMotionAt()).isEqualTo("2026-08-27T12:00:00");
        assertThat(cameras.get(0).lastMotionClipId()).isEqualTo("42");
        assertThat(cameras.get(0).name()).isEqualTo("Frontdoor");
    }

    @Test
    void kameraOhneBewegungTraegtNullFelder() {
        when(client.listCameras(false)).thenReturn(List.of(new BlinkSidecarClient.SidecarCamera(
                "123", "Frontdoor", "doorbell", true, "ok", "Zuhause", true)));
        when(motionService.lastMotion("123")).thenReturn(Optional.empty());

        assertThat(service.listCameras().get(0).lastMotionAt()).isNull();
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
