package com.household.manager.controller;

import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.dto.alexa.*;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.service.AlexaAnnouncementService;
import com.household.manager.service.AlexaDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** REST-Endpunkte der Alexa-Integration. Basispfad ergibt sich aus dem Servlet-Context-Path. */
@RestController
@RequestMapping("/v1/alexa")
@RequiredArgsConstructor
public class AlexaController {

    private final AlexaSidecarClient sidecarClient;
    private final AlexaDeviceService deviceService;
    private final AlexaAnnouncementService announcementService;

    // ---------- Auth (Browser-Login ueber den Sidecar) ----------

    /** Startet den Browser-Login und liefert die URL, die der Nutzer im Browser oeffnet. */
    @PostMapping("/auth/proxy/start")
    public AlexaProxyStartResponse startProxyLogin() {
        return new AlexaProxyStartResponse(sidecarClient.startLogin());
    }

    @GetMapping("/auth/status")
    public AlexaAuthStatusResponse status() {
        AlexaSidecarClient.SidecarStatus s = sidecarClient.getStatus();
        return new AlexaAuthStatusResponse(s.loggedIn(), s.accountName(), false, null);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout() {
        sidecarClient.logout();
        return ResponseEntity.noContent().build();
    }

    // ---------- Devices ----------

    @GetMapping("/devices")
    public List<AlexaDeviceResponse> devices(@RequestParam(defaultValue = "false") boolean rescan) {
        List<AlexaDevice> devices = rescan ? deviceService.rescan() : deviceService.getDevices();
        return devices.stream()
                .map(d -> new AlexaDeviceResponse(
                        d.getSerialNumber(), d.getName(), d.getDeviceType(), d.isTtsCapable()))
                .toList();
    }

    // ---------- Announce ----------

    @PostMapping("/announce")
    public ResponseEntity<Void> announce(@RequestBody AnnounceRequest request) {
        AlexaTtsMode mode = request.mode() == null ? AlexaTtsMode.ANNOUNCE : request.mode();
        announcementService.announce(request.text(), request.serialNumbers(), mode);
        return ResponseEntity.noContent().build();
    }
}
