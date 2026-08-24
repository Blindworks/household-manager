package com.household.manager.network;

import com.household.manager.service.SeriesRange;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Status-, Historien- und Geraeteverwaltungs-API des Netzwerk-Monitorings. Bewusst duenn -
 * alle Logik steckt in {@link NetworkStatusService}, {@link NetworkHistoryService},
 * {@link NetworkSpeedtestService} und {@link NetworkDeviceService}.
 */
@RestController
@RequestMapping("/v1/network")
@RequiredArgsConstructor
public class NetworkController {

    private final NetworkStatusService statusService;
    private final NetworkHistoryService historyService;
    private final NetworkSpeedtestService speedtestService;
    private final NetworkDeviceService deviceService;

    @GetMapping("/status")
    public ResponseEntity<NetworkDtos.StatusResponse> status() {
        return ResponseEntity.ok(statusService.getStatus());
    }

    @GetMapping("/history")
    public ResponseEntity<NetworkDtos.HistoryResponse> history(
            @RequestParam(required = false, defaultValue = "WEEK") SeriesRange range) {
        return ResponseEntity.ok(historyService.getHistory(range));
    }

    @PostMapping("/speedtest")
    public ResponseEntity<NetworkDtos.SpeedtestSummary> speedtest() {
        return ResponseEntity.ok(NetworkDtos.SpeedtestSummary.from(speedtestService.runManual()));
    }

    @GetMapping("/devices")
    public ResponseEntity<List<NetworkDtos.DeviceAdminResponse>> listDevices() {
        return ResponseEntity.ok(deviceService.list());
    }

    @PostMapping("/devices")
    public ResponseEntity<NetworkDtos.DeviceAdminResponse> createDevice(
            @RequestBody NetworkDtos.DeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deviceService.create(request));
    }

    @PutMapping("/devices/{id}")
    public ResponseEntity<NetworkDtos.DeviceAdminResponse> updateDevice(
            @PathVariable Long id, @RequestBody NetworkDtos.DeviceRequest request) {
        return ResponseEntity.ok(deviceService.update(id, request));
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {
        deviceService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
