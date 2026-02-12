package com.household.manager.tapo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.tapo.config.TapoProperties;
import com.household.manager.tapo.dto.TapoDeviceInfoDto;
import com.household.manager.tapo.dto.TapoEnergyUsageDto;
import com.household.manager.tapo.exception.TapoConnectionException;
import com.household.manager.tapo.exception.TapoException;
import com.household.manager.tapo.protocol.KlapProtocolClientV2;
import com.household.manager.tapo.protocol.KlapSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service for controlling Tapo devices using KLAP protocol.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TapoDeviceService {

    @Qualifier("klapProtocolClientV2")
    private final KlapProtocolClientV2 klapProtocolClient;
    private final TapoProperties tapoProperties;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, KlapSession> sessionCache = new ConcurrentHashMap<>();

    // Generate terminal UUID once per service instance
    private final String terminalUuid = generateTerminalUuid();

    private static String generateTerminalUuid() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return Base64.getEncoder().encodeToString(bb.array());
    }

    public void connect(String deviceIp) {
        sessionCache.put(deviceIp, createSession(deviceIp));
    }

    public void turnOn(String deviceIp) {
        withReconnect(deviceIp, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("device_on", true)
            ));
            return null;
        });
    }

    public void turnOff(String deviceIp) {
        withReconnect(deviceIp, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("device_on", false)
            ));
            return null;
        });
    }

    public TapoDeviceInfoDto getDeviceInfo(String deviceIp) {
        return withReconnect(deviceIp, session -> {
            // Use multipleRequest format like python-kasa
            Map<String, Object> request = Map.of(
                    "method", "multipleRequest",
                    "request_time_milis", System.currentTimeMillis(),
                    "terminal_uuid", terminalUuid,
                    "params", Map.of(
                            "requests", List.of(Map.of("method", "get_device_info"))
                    )
            );
            Map<String, Object> response = send(session, request);
            return parseDeviceInfo(response);
        });
    }

    public TapoEnergyUsageDto getEnergyUsage(String deviceIp) {
        return withReconnect(deviceIp, session -> {
            Map<String, Object> response = send(session, Map.of("method", "get_energy_usage"));
            return parseEnergyUsage(response);
        });
    }

    public void setBrightness(String deviceIp, int brightness) {
        if (brightness < 1 || brightness > 100) {
            throw new IllegalArgumentException("brightness must be between 1 and 100");
        }

        withReconnect(deviceIp, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("brightness", brightness)
            ));
            return null;
        });
    }

    public void setColorTemp(String deviceIp, int colorTemp) {
        if (colorTemp < 2500 || colorTemp > 6500) {
            throw new IllegalArgumentException("colorTemp must be between 2500 and 6500");
        }

        withReconnect(deviceIp, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("color_temp", colorTemp)
            ));
            return null;
        });
    }

    public void setColor(String deviceIp, int hue, int saturation, int brightness) {
        if (hue < 0 || hue > 360) {
            throw new IllegalArgumentException("hue must be between 0 and 360");
        }
        if (saturation < 0 || saturation > 100) {
            throw new IllegalArgumentException("saturation must be between 0 and 100");
        }
        if (brightness < 1 || brightness > 100) {
            throw new IllegalArgumentException("brightness must be between 1 and 100");
        }

        withReconnect(deviceIp, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of(
                            "hue", hue,
                            "saturation", saturation,
                            "brightness", brightness
                    )
            ));
            return null;
        });
    }

    private Map<String, Object> send(KlapSession session, Map<String, Object> request) {
        return klapProtocolClient.sendRequest(session, request);
    }

    private KlapSession getOrCreateSession(String deviceIp) {
        return sessionCache.computeIfAbsent(deviceIp, this::createSession);
    }

    private KlapSession createSession(String deviceIp) {
        String email = tapoProperties.getEmail();
        String password = tapoProperties.getPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new TapoConnectionException("Tapo credentials are not configured (tapo.email / tapo.password)");
        }

        log.info("Opening KLAP session for device {}", deviceIp);
        return klapProtocolClient.handshake(deviceIp, email, password);
    }

    private <T> T withReconnect(String deviceIp, TapoOperation<T> operation) {
        KlapSession session = getOrCreateSession(deviceIp);
        try {
            return operation.execute(session);
        } catch (TapoException ex) {
            log.warn("Tapo request failed for {}, reconnecting once: {}", deviceIp, ex.getMessage());
            KlapSession refreshed = createSession(deviceIp);
            sessionCache.put(deviceIp, refreshed);
            return operation.execute(refreshed);
        }
    }

    private TapoDeviceInfoDto parseDeviceInfo(Map<String, Object> response) {
        try {
            Object result = response.get("result");
            if (result == null) {
                log.warn("No 'result' field in device info response, using root object");
                result = response;
            }

            // Handle multipleRequest response format
            if (result instanceof Map) {
                Map<String, Object> resultMap = (Map<String, Object>) result;
                Object responses = resultMap.get("responses");
                if (responses instanceof List) {
                    List<Map<String, Object>> responsesList = (List<Map<String, Object>>) responses;
                    if (!responsesList.isEmpty()) {
                        Map<String, Object> firstResponse = responsesList.get(0);
                        result = firstResponse.get("result");
                    }
                }
            }

            return objectMapper.convertValue(result, TapoDeviceInfoDto.class);
        } catch (Exception ex) {
            log.error("Failed to parse device info response: {}", response, ex);
            throw new TapoException("Failed to parse device info", ex);
        }
    }

    private TapoEnergyUsageDto parseEnergyUsage(Map<String, Object> response) {
        try {
            Object result = response.get("result");
            if (result == null) {
                log.warn("No 'result' field in energy usage response, using root object");
                result = response;
            }
            return objectMapper.convertValue(result, TapoEnergyUsageDto.class);
        } catch (Exception ex) {
            log.error("Failed to parse energy usage response: {}", response, ex);
            throw new TapoException("Failed to parse energy usage", ex);
        }
    }

    @FunctionalInterface
    private interface TapoOperation<T> {
        T execute(KlapSession session);
    }
}
