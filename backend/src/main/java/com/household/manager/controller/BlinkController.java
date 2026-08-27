package com.household.manager.controller;

import com.household.manager.blink.BlinkCameraService;
import com.household.manager.blink.BlinkMotionService;
import com.household.manager.blink.BlinkSidecarClient.SidecarClip;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST-API des Blink-Kamera-Dashboards. Medien (Standbilder, Clips) werden vom
 * Sidecar durchgestreamt — das Frontend spricht nie direkt mit ihm.
 * Rollen: Lesen + Schnappschuss KIOSK, Scharf/Unscharf MEMBER (SecurityConfig, eigener Task).
 */
@RestController
@RequestMapping("/v1/blink")
@RequiredArgsConstructor
public class BlinkController {

    private final BlinkCameraService cameraService;
    private final BlinkMotionService motionService;

    @GetMapping("/cameras")
    public List<BlinkCameraService.CameraResponse> getCameras() {
        return cameraService.listCameras();
    }

    /** Maschinen-Endpunkt: Bewegungsmeldungen des Sidecars (SERVICE-Authority). */
    @PostMapping("/motion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportMotion(@RequestBody List<BlinkMotionService.MotionReport> motions) {
        motionService.processMotions(motions);
    }

    @PostMapping("/cameras/{cameraId}/arm")
    public void armCamera(@PathVariable String cameraId) {
        cameraService.setCameraArmed(cameraId, true);
    }

    @PostMapping("/cameras/{cameraId}/disarm")
    public void disarmCamera(@PathVariable String cameraId) {
        cameraService.setCameraArmed(cameraId, false);
    }

    @PostMapping("/system/{syncName}/arm")
    public void armSystem(@PathVariable String syncName) {
        cameraService.setSystemArmed(syncName, true);
    }

    @PostMapping("/system/{syncName}/disarm")
    public void disarmSystem(@PathVariable String syncName) {
        cameraService.setSystemArmed(syncName, false);
    }

    @PostMapping(value = "/cameras/{cameraId}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public byte[] snapshot(@PathVariable String cameraId) {
        return cameraService.snapshot(cameraId);
    }

    @GetMapping(value = "/cameras/{cameraId}/thumbnail", produces = MediaType.IMAGE_JPEG_VALUE)
    public byte[] thumbnail(@PathVariable String cameraId) {
        return cameraService.thumbnail(cameraId);
    }

    @GetMapping("/cameras/{cameraId}/clips")
    public List<SidecarClip> clips(@PathVariable String cameraId) {
        return cameraService.listClips(cameraId);
    }

    /**
     * Rueckgabetyp Resource, nicht byte[]: Spring beantwortet damit auch
     * HTTP-Range-Anfragen — Safari (iPhone-PWA) spielt ein <video> sonst nicht ab.
     * Real gelernter Punkt, kein Stilwunsch.
     */
    @GetMapping("/cameras/{cameraId}/clips/{clipId}")
    public ResponseEntity<Resource> clip(@PathVariable String cameraId, @PathVariable String clipId) {
        byte[] data = cameraService.clip(cameraId, clipId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new ByteArrayResource(data));
    }
}
