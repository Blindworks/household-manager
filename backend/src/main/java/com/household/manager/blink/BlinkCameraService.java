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
    private final BlinkMotionService motionService;

    /** Kamera fuer das Frontend: Sidecar-Daten plus letzte Bewegung (null = keine bekannt). */
    public record CameraResponse(String cameraId, String name, String type, boolean armed,
                                  String battery, String syncName, boolean syncArmed,
                                  String lastMotionAt, String lastMotionClipId) {}

    public List<CameraResponse> listCameras() {
        return client.listCameras(false).stream().map(this::toResponse).toList();
    }

    private CameraResponse toResponse(SidecarCamera camera) {
        var last = motionService.lastMotion(camera.cameraId()).orElse(null);
        return new CameraResponse(camera.cameraId(), camera.name(), camera.type(),
                camera.armed(), camera.battery(), camera.syncName(), camera.syncArmed(),
                last == null ? null : last.createdAt(),
                last == null ? null : last.clipId());
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
