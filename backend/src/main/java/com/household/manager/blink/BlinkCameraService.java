package com.household.manager.blink;

import com.household.manager.audit.AuditService;
import com.household.manager.blink.BlinkSidecarClient.SidecarCamera;
import com.household.manager.blink.BlinkSidecarClient.SidecarClip;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fachschicht des Kamera-Dashboards: Proxy zum Sidecar plus Audit und
 * sofortiges Nachpollen nach Schaltaktionen (Muster NukiLockService).
 * Der Schnappschuss ist bewusst ohne Audit — er ändert nichts am Systemzustand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BlinkCameraService {

    private final BlinkSidecarClient client;
    private final BlinkPollingService pollingService;
    private final AuditService auditService;

    public List<SidecarCamera> listCameras() {
        return client.listCameras(false);
    }

    public void setCameraArmed(String cameraId, boolean armed) {
        client.setCameraArmed(cameraId, armed);
        auditService.record(armed ? "blink.camera.arm" : "blink.camera.disarm", cameraId);
        // pollForced, nicht poll: async_arm() aendert den lokalen Zustand nicht,
        // und ein ungezwungener Refresh laeuft in blinkpys 30-s-Drossel.
        pollingService.pollForced();
    }

    public void setSystemArmed(String syncName, boolean armed) {
        client.setSyncArmed(syncName, armed);
        auditService.record(armed ? "blink.system.arm" : "blink.system.disarm", syncName);
        pollingService.pollForced();
    }

    public byte[] snapshot(String cameraId) {
        return client.snapshot(cameraId);
    }

    public byte[] thumbnail(String cameraId) {
        return client.thumbnail(cameraId);
    }

    public List<SidecarClip> listClips(String cameraId) {
        return client.listClips(cameraId);
    }

    public byte[] clip(String cameraId, String clipId) {
        return client.clip(cameraId, clipId);
    }
}
