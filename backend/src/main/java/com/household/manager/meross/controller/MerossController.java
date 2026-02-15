package com.household.manager.meross.controller;

import com.household.manager.meross.dto.MerossPlugResponse;
import com.household.manager.meross.service.MerossDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/meross")
@RequiredArgsConstructor
public class MerossController {

    private final MerossDeviceService merossDeviceService;

    @GetMapping("/devices")
    public ResponseEntity<List<MerossPlugResponse>> discoverPlugs() {
        return ResponseEntity.ok(merossDeviceService.discoverPlugs());
    }

    @GetMapping("/devices/{deviceId:.+}/status")
    public ResponseEntity<MerossPlugResponse> getStatus(@PathVariable String deviceId) {
        return ResponseEntity.ok(merossDeviceService.getStatus(deviceId));
    }

    @PostMapping("/devices/{deviceId:.+}/on")
    public ResponseEntity<Void> turnOn(@PathVariable String deviceId) {
        merossDeviceService.turnOn(deviceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{deviceId:.+}/off")
    public ResponseEntity<Void> turnOff(@PathVariable String deviceId) {
        merossDeviceService.turnOff(deviceId);
        return ResponseEntity.noContent().build();
    }
}
