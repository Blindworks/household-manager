package com.household.manager.controller;

import com.household.manager.alexa.AlexaSidecarClient;
import com.household.manager.alexa.AlexaTtsMode;
import com.household.manager.dto.alexa.*;
import com.household.manager.model.entity.AlexaDevice;
import com.household.manager.model.entity.AlexaScheduledAnnouncement;
import com.household.manager.service.AlexaAnnouncementService;
import com.household.manager.service.AlexaDeviceService;
import com.household.manager.service.AlexaScheduledAnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;

/** REST-Endpunkte der Alexa-Integration. Basispfad ergibt sich aus dem Servlet-Context-Path. */
@RestController
@RequestMapping("/v1/alexa")
@RequiredArgsConstructor
public class AlexaController {

    private final AlexaSidecarClient sidecarClient;
    private final AlexaDeviceService deviceService;
    private final AlexaAnnouncementService announcementService;
    private final AlexaScheduledAnnouncementService scheduledService;

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

    // ---------- Scheduled ----------

    @GetMapping("/scheduled-announcements")
    public List<ScheduledAnnouncementResponse> listScheduled() {
        return scheduledService.getAll().stream().map(this::toResponse).toList();
    }

    @PostMapping("/scheduled-announcements")
    public ScheduledAnnouncementResponse createScheduled(@RequestBody ScheduledAnnouncementRequest request) {
        return toResponse(scheduledService.create(toEntity(request)));
    }

    @PutMapping("/scheduled-announcements/{id}")
    public ScheduledAnnouncementResponse updateScheduled(@PathVariable Long id,
                                                         @RequestBody ScheduledAnnouncementRequest request) {
        return toResponse(scheduledService.update(id, toEntity(request)));
    }

    @DeleteMapping("/scheduled-announcements/{id}")
    public ResponseEntity<Void> deleteScheduled(@PathVariable Long id) {
        scheduledService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Mapping ----------

    private AlexaScheduledAnnouncement toEntity(ScheduledAnnouncementRequest r) {
        return AlexaScheduledAnnouncement.builder()
                .text(r.text())
                .timeOfDay(r.timeOfDay())
                .weekdays(String.join(",", r.weekdays()))
                .mode(r.mode() == null ? AlexaTtsMode.ANNOUNCE : r.mode())
                .enabled(r.enabled())
                .targetSerialNumbers(new HashSet<>(r.serialNumbers()))
                .build();
    }

    private ScheduledAnnouncementResponse toResponse(AlexaScheduledAnnouncement a) {
        List<String> weekdays = a.getWeekdays() == null || a.getWeekdays().isBlank()
                ? List.of()
                : List.of(a.getWeekdays().split(","));
        return new ScheduledAnnouncementResponse(
                a.getId(), a.getText(), a.getTimeOfDay(), weekdays,
                List.copyOf(a.getTargetSerialNumbers()), a.getMode(), a.isEnabled(),
                a.getLastRun(), a.getLastError());
    }
}
