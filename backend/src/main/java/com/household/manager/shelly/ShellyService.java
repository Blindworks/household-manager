package com.household.manager.shelly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.shelly.dto.ShellyStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
public class ShellyService {

    private final ShellyProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ShellyService(ShellyProperties properties, RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .build();
    }

    public List<ShellyStatusDto> getAllDevicesStatus() {
        return properties.getDevices().stream()
                .map(this::getLiveStatus)
                .toList();
    }

    public ShellyStatusDto getLiveStatus(ShellyProperties.ShellyDeviceConfig device) {
        String url = "http://" + device.getIp() + "/rpc/Switch.GetStatus?id=0";
        try {
            String response = restTemplate.getForObject(url, String.class);
            return parseStatusResponse(device, response);
        } catch (RestClientException e) {
            log.warn("Shelly device '{}' at {} unreachable: {}", device.getName(), device.getIp(), e.getMessage());
            return new ShellyStatusDto(device.getName(), device.getIp(), false, null, null, null, null, false);
        }
    }

    public void turnOn(String deviceName) {
        sendSwitchCommand(deviceName, true);
    }

    public void turnOff(String deviceName) {
        sendSwitchCommand(deviceName, false);
    }

    private void sendSwitchCommand(String deviceName, boolean on) {
        ShellyProperties.ShellyDeviceConfig device = findDevice(deviceName);
        String url = "http://" + device.getIp() + "/rpc/Switch.Set?id=0&on=" + on;
        try {
            restTemplate.getForObject(url, String.class);
            log.info("Shelly '{}' turned {}", deviceName, on ? "on" : "off");
        } catch (RestClientException e) {
            throw new ShellyException("Failed to control Shelly device '" + deviceName + "': " + e.getMessage(), e);
        }
    }

    private ShellyProperties.ShellyDeviceConfig findDevice(String deviceName) {
        return properties.getDevices().stream()
                .filter(d -> d.getName().equals(deviceName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Shelly device not found: " + deviceName));
    }

    private ShellyStatusDto parseStatusResponse(ShellyProperties.ShellyDeviceConfig device, String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            boolean output = root.path("output").asBoolean(false);
            double power = root.path("apower").asDouble(0.0);
            double voltage = root.path("voltage").asDouble(0.0);
            double current = root.path("current").asDouble(0.0);
            double totalEnergy = root.path("aenergy").path("total").asDouble(0.0);
            return new ShellyStatusDto(device.getName(), device.getIp(), output, power, voltage, current, totalEnergy, true);
        } catch (Exception e) {
            log.error("Failed to parse Shelly response from '{}': {}", device.getName(), e.getMessage());
            return new ShellyStatusDto(device.getName(), device.getIp(), false, null, null, null, null, false);
        }
    }
}
