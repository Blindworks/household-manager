package com.household.manager.tapo.controller;

import com.household.manager.tapo.dto.TapoDeviceInfoDto;
import com.household.manager.tapo.dto.TapoEnergyUsageDto;
import com.household.manager.tapo.model.TapoDevice;
import com.household.manager.tapo.service.TapoDiscoveryService;
import com.household.manager.tapo.service.TapoDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tapo")
@RequiredArgsConstructor
public class TapoController {

    private final TapoDiscoveryService tapoDiscoveryService;
    private final TapoDeviceService tapoDeviceService;

    @GetMapping("/devices")
    public ResponseEntity<List<TapoDevice>> discoverDevices() {
        return ResponseEntity.ok(tapoDiscoveryService.discoverDevices());
    }

    @GetMapping("/devices/{deviceId:.+}/info")
    public ResponseEntity<TapoDeviceInfoDto> getDeviceInfo(@PathVariable String deviceId) {
        return ResponseEntity.ok(tapoDeviceService.getDeviceInfo(deviceId));
    }

    @PostMapping("/devices/{deviceId:.+}/on")
    public ResponseEntity<Void> turnOn(@PathVariable String deviceId) {
        tapoDeviceService.turnOn(deviceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{deviceId:.+}/off")
    public ResponseEntity<Void> turnOff(@PathVariable String deviceId) {
        tapoDeviceService.turnOff(deviceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{deviceId:.+}/brightness")
    public ResponseEntity<Void> setBrightness(@PathVariable String deviceId, @RequestBody BrightnessRequest request) {
        tapoDeviceService.setBrightness(deviceId, request.brightness());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{deviceId:.+}/color")
    public ResponseEntity<Void> setColor(@PathVariable String deviceId, @RequestBody ColorRequest request) {
        tapoDeviceService.setColor(deviceId, request.hue(), request.saturation(), request.brightness());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{deviceId:.+}/color-temp")
    public ResponseEntity<Void> setColorTemp(@PathVariable String deviceId, @RequestBody ColorTempRequest request) {
        tapoDeviceService.setColorTemp(deviceId, request.colorTemp());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/devices/{deviceId:.+}/energy")
    public ResponseEntity<TapoEnergyUsageDto> getEnergy(@PathVariable String deviceId) {
        return ResponseEntity.ok(tapoDeviceService.getEnergyUsage(deviceId));
    }

    public record BrightnessRequest(int brightness) {
    }

    public record ColorRequest(int hue, int saturation, int brightness) {
    }

    public record ColorTempRequest(int colorTemp) {
    }
}
