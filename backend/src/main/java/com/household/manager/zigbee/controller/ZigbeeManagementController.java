package com.household.manager.zigbee.controller;

import com.household.manager.zigbee.dto.PermitJoinRequest;
import com.household.manager.zigbee.dto.PurgeResultResponse;
import com.household.manager.zigbee.dto.RenameDeviceRequest;
import com.household.manager.zigbee.service.ZigbeeDeviceManagementService;
import com.household.manager.zigbee.service.ZigbeeDevicePurgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Schreibende Zigbee-Geraeteverwaltung. Alle Endpunkte ADMIN ueber methodenspezifische
 * Matcher in SecurityConfig — GET /devices/{name}/measurements liegt unter demselben
 * Pfadpraefix und muss KIOSK bleiben.
 */
@RestController
@RequestMapping("/v1/zigbee")
@RequiredArgsConstructor
public class ZigbeeManagementController {

    private final ZigbeeDeviceManagementService managementService;
    private final ZigbeeDevicePurgeService purgeService;

    @PostMapping("/bridge/permit-join")
    public ResponseEntity<Void> openPermitJoin(@RequestBody PermitJoinRequest request) {
        managementService.permitJoin(request.seconds());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/bridge/permit-join")
    public ResponseEntity<Void> closePermitJoin() {
        managementService.closePermitJoin();
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/devices/{ieee}/name")
    public ResponseEntity<Void> rename(@PathVariable String ieee, @RequestBody RenameDeviceRequest request) {
        managementService.rename(ieee, request.friendlyName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{ieee}/interview")
    public ResponseEntity<Void> interview(@PathVariable String ieee) {
        managementService.interview(ieee);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{ieee}/configure")
    public ResponseEntity<Void> configure(@PathVariable String ieee) {
        managementService.configure(ieee);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/devices/{ieee}")
    public ResponseEntity<Void> remove(@PathVariable String ieee,
                                       @RequestParam(defaultValue = "false") boolean force) {
        managementService.remove(ieee, force);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/devices/local/{id}")
    public ResponseEntity<PurgeResultResponse> purge(@PathVariable Long id) {
        var result = purgeService.purge(id);
        return ResponseEntity.ok(new PurgeResultResponse(result.friendlyName(), result.measurements(), result.entities()));
    }
}
