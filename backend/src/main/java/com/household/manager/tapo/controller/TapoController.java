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

    @GetMapping("/devices/{ip:.+}/info")
    public ResponseEntity<TapoDeviceInfoDto> getDeviceInfo(@PathVariable String ip) {
        return ResponseEntity.ok(tapoDeviceService.getDeviceInfo(ip));
    }

    @PostMapping("/devices/{ip:.+}/on")
    public ResponseEntity<Void> turnOn(@PathVariable String ip) {
        tapoDeviceService.turnOn(ip);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{ip:.+}/off")
    public ResponseEntity<Void> turnOff(@PathVariable String ip) {
        tapoDeviceService.turnOff(ip);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{ip:.+}/brightness")
    public ResponseEntity<Void> setBrightness(@PathVariable String ip, @RequestBody BrightnessRequest request) {
        tapoDeviceService.setBrightness(ip, request.brightness());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{ip:.+}/color")
    public ResponseEntity<Void> setColor(@PathVariable String ip, @RequestBody ColorRequest request) {
        tapoDeviceService.setColor(ip, request.hue(), request.saturation(), request.brightness());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{ip:.+}/color-temp")
    public ResponseEntity<Void> setColorTemp(@PathVariable String ip, @RequestBody ColorTempRequest request) {
        tapoDeviceService.setColorTemp(ip, request.colorTemp());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/devices/{ip:.+}/energy")
    public ResponseEntity<TapoEnergyUsageDto> getEnergy(@PathVariable String ip) {
        return ResponseEntity.ok(tapoDeviceService.getEnergyUsage(ip));
    }

    public record BrightnessRequest(int brightness) {
    }

    public record ColorRequest(int hue, int saturation, int brightness) {
    }

    public record ColorTempRequest(int colorTemp) {
    }
}
