package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.tapo.dto.TapoDeviceInfoResponse;
import com.household.manager.tapo.dto.TapoDiscoveryDeviceResponse;
import com.household.manager.tapo.dto.TapoEnergyUsageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/tapo")
@RequiredArgsConstructor
@Slf4j
public class TapoController {

    private final TapoDeviceService tapoDeviceService;
    private final TapoCloudService tapoCloudService;

    @GetMapping("/devices")
    public ResponseEntity<List<TapoDiscoveryDeviceResponse>> getDevices() {
        log.info("GET /api/tapo/devices - Abrufen aller Tapo-Geraete");
        List<TapoCloudDevice> devices = tapoDeviceService.discoverCloudDevices();
        List<TapoDiscoveryDeviceResponse> response = devices.stream()
                .map(this::toDiscoveryResponse)
                .toList();
        log.info("Gefunden: {} Tapo-Geraete", response.size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/devices/{deviceId}/info")
    public ResponseEntity<TapoDeviceInfoResponse> getDeviceInfo(@PathVariable String deviceId) {
        log.info("GET /api/tapo/devices/{}/info - Geraeteinfo abrufen", deviceId);
        JsonNode info = tapoCloudService.getDeviceInfo(deviceId);
        return ResponseEntity.ok(toDeviceInfoResponse(info));
    }

    @GetMapping("/devices/{deviceId}/energy")
    public ResponseEntity<TapoEnergyUsageResponse> getEnergyUsage(@PathVariable String deviceId) {
        log.info("GET /api/tapo/devices/{}/energy - Energieverbrauch abrufen", deviceId);
        JsonNode energy = tapoDeviceService.getEnergyUsage(deviceId);
        return ResponseEntity.ok(toEnergyUsageResponse(energy));
    }

    @PostMapping("/devices/{deviceId}/on")
    public ResponseEntity<Void> turnOn(@PathVariable String deviceId) {
        log.info("POST /api/tapo/devices/{}/on - Geraet einschalten", deviceId);
        tapoDeviceService.turnOn(deviceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{deviceId}/off")
    public ResponseEntity<Void> turnOff(@PathVariable String deviceId) {
        log.info("POST /api/tapo/devices/{}/off - Geraet ausschalten", deviceId);
        tapoDeviceService.turnOff(deviceId);
        return ResponseEntity.noContent().build();
    }

    private TapoDiscoveryDeviceResponse toDiscoveryResponse(TapoCloudDevice device) {
        String decodedAlias = tapoCloudService.decodeAlias(device.alias());
        String displayName = Optional.ofNullable(decodedAlias)
                .filter(n -> !n.isBlank())
                .orElseGet(() -> Optional.ofNullable(device.deviceName())
                        .filter(n -> !n.isBlank())
                        .orElse("Tapo Device"));

        return TapoDiscoveryDeviceResponse.builder()
                .deviceId(device.deviceId())
                .deviceName(displayName)
                .deviceType(device.deviceType())
                .model(device.model())
                .ip(null)
                .online(true) // Cloud status field is unreliable for Tapo; actual status via passthrough
                .fwVer(device.fwVer())
                .build();
    }

    private TapoDeviceInfoResponse toDeviceInfoResponse(JsonNode info) {
        return TapoDeviceInfoResponse.builder()
                .deviceId(textOrNull(info, "device_id"))
                .type(textOrNull(info, "type"))
                .model(textOrNull(info, "model"))
                .hwVer(textOrNull(info, "hw_ver"))
                .fwVer(textOrNull(info, "fw_ver"))
                .mac(textOrNull(info, "mac"))
                .nickname(decodeBase64Field(info, "nickname"))
                .deviceOn(boolOrNull(info, "device_on"))
                .onTime(intOrNull(info, "on_time"))
                .signalLevel(intOrNull(info, "signal_level"))
                .rssi(intOrNull(info, "rssi"))
                .ip(textOrNull(info, "ip"))
                .ssid(decodeBase64Field(info, "ssid"))
                .overheated(boolOrNull(info, "overheated"))
                .build();
    }

    private TapoEnergyUsageResponse toEnergyUsageResponse(JsonNode energy) {
        return TapoEnergyUsageResponse.builder()
                .todayRuntime(intOrNull(energy, "today_runtime"))
                .monthRuntime(intOrNull(energy, "month_runtime"))
                .todayEnergy(intOrNull(energy, "today_energy"))
                .monthEnergy(intOrNull(energy, "month_energy"))
                .currentPower(intOrNull(energy, "current_power"))
                .build();
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private Boolean boolOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asBoolean();
    }

    private String decodeBase64Field(JsonNode node, String field) {
        String raw = textOrNull(node, field);
        return raw == null ? null : tapoCloudService.decodeAlias(raw);
    }
}
