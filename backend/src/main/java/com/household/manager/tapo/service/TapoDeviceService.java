package com.household.manager.tapo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.tapo.config.TapoProperties;
import com.household.manager.tapo.dto.TapoDeviceInfoDto;
import com.household.manager.tapo.dto.TapoEnergyUsageDto;
import com.household.manager.tapo.exception.TapoConnectionException;
import com.household.manager.tapo.exception.TapoException;
import com.household.manager.tapo.protocol.TapoCloudClient;
import com.household.manager.tapo.protocol.TapoCloudSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service for controlling Tapo devices via Cloud API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TapoDeviceService {

    private final TapoCloudClient cloudClient;
    private final TapoProperties tapoProperties;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, TapoCloudSession> sessionCache = new ConcurrentHashMap<>();
    private String cachedToken;
    private long tokenCreatedAt;

    public void connect(String deviceId) {
        sessionCache.put(deviceId, new TapoCloudSession(getOrRefreshToken(), deviceId));
    }

    public void turnOn(String deviceId) {
        assertPowerControlSupported(deviceId);
        withReconnect(deviceId, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("device_on", true)
            ));
            return null;
        });
    }

    public void turnOff(String deviceId) {
        assertPowerControlSupported(deviceId);
        withReconnect(deviceId, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("device_on", false)
            ));
            return null;
        });
    }

    public TapoDeviceInfoDto getDeviceInfo(String deviceId) {
        return withReconnect(deviceId, session -> {
            Map<String, Object> response = send(session, Map.of("method", "get_device_info"));
            return parseDeviceInfo(response);
        });
    }

    public TapoEnergyUsageDto getEnergyUsage(String deviceId) {
        return withReconnect(deviceId, session -> {
            Map<String, Object> response = send(session, Map.of("method", "get_energy_usage"));
            return parseEnergyUsage(response);
        });
    }

    public void setBrightness(String deviceId, int brightness) {
        if (brightness < 1 || brightness > 100) {
            throw new IllegalArgumentException("brightness must be between 1 and 100");
        }

        withReconnect(deviceId, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("brightness", brightness)
            ));
            return null;
        });
    }

    public void setColorTemp(String deviceId, int colorTemp) {
        if (colorTemp < 2500 || colorTemp > 6500) {
            throw new IllegalArgumentException("colorTemp must be between 2500 and 6500");
        }

        withReconnect(deviceId, session -> {
            send(session, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("color_temp", colorTemp)
            ));
            return null;
        });
    }

    public void setColor(String deviceId, int hue, int saturation, int brightness) {
        if (hue < 0 || hue > 360) {
            throw new IllegalArgumentException("hue must be between 0 and 360");
        }
        if (saturation < 0 || saturation > 100) {
            throw new IllegalArgumentException("saturation must be between 0 and 100");
        }
        if (brightness < 1 || brightness > 100) {
            throw new IllegalArgumentException("brightness must be between 1 and 100");
        }

        withReconnect(deviceId, session -> {
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

    /**
     * Lists all devices from Tapo Cloud account.
     */
    public List<Map<String, Object>> listDevices() {
        String token = getOrRefreshToken();
        JsonNode deviceList = cloudClient.getDeviceList(token);
        return objectMapper.convertValue(deviceList, List.class);
    }

    private Map<String, Object> send(TapoCloudSession session, Map<String, Object> request) {
        Map<String, Object> response = cloudClient.sendDeviceRequest(session.getToken(), session.getDeviceId(), request);
        throwIfDeviceRequestFailed(response);
        return response;
    }

    private TapoCloudSession getOrCreateSession(String deviceId) {
        return sessionCache.computeIfAbsent(deviceId, key ->
            new TapoCloudSession(getOrRefreshToken(), key)
        );
    }

    private String getOrRefreshToken() {
        String email = tapoProperties.getEmail();
        String password = tapoProperties.getPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new TapoConnectionException("Tapo credentials are not configured (tapo.email / tapo.password)");
        }

        long now = System.currentTimeMillis();
        if (cachedToken == null || (now - tokenCreatedAt) > tapoProperties.getCloudTokenExpiryMs()) {
            log.info("Authenticating with Tapo Cloud");
            cachedToken = cloudClient.login(email, password);
            tokenCreatedAt = now;
        }

        return cachedToken;
    }

    private <T> T withReconnect(String deviceId, CloudOperation<T> operation) {
        TapoCloudSession session = getOrCreateSession(deviceId);
        try {
            return operation.execute(session);
        } catch (TapoException ex) {
            // Don't retry for device-specific errors (unsupported operations, etc.)
            String message = ex.getMessage();
            if (message != null && (
                    message.contains("does not support this operation") ||
                    message.contains("Module not support") ||
                    message.contains("not binded to the device"))) {
                log.debug("Device {} does not support this operation: {}", deviceId, message);
                throw ex; // Don't retry, it's a device limitation
            }

            // Retry with fresh token for auth/connection errors
            log.warn("Tapo cloud request failed for {}, refreshing token and retrying: {}", deviceId, ex.getMessage());
            cachedToken = null;
            TapoCloudSession refreshed = new TapoCloudSession(getOrRefreshToken(), session.getDeviceId());
            sessionCache.put(deviceId, refreshed);
            return operation.execute(refreshed);
        }
    }

    private TapoDeviceInfoDto parseDeviceInfo(Map<String, Object> response) {
        try {
            // Check for API errors (e.g., "Module not support" for cameras/hubs)
            if (response.containsKey("method")) {
                Map<String, Object> methodResponse = (Map<String, Object>) response.get("method");
                if (methodResponse != null && methodResponse.containsKey("err_code")) {
                    int errCode = (int) methodResponse.get("err_code");
                    String errMsg = (String) methodResponse.getOrDefault("err_msg", "Unknown error");
                    if (errCode == -2001) {
                        throw new TapoException("Device does not support this operation: " + errMsg);
                    }
                }
            }

            Object result = response.get("result");
            if (result == null) {
                log.warn("No 'result' field in device info response, using root object");
                result = response;
            }
            return objectMapper.convertValue(result, TapoDeviceInfoDto.class);
        } catch (TapoException ex) {
            throw ex;
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

    private void assertPowerControlSupported(String deviceId) {
        Optional<Map<String, Object>> device = findDeviceById(deviceId);
        if (device.isEmpty()) {
            return;
        }

        String deviceType = String.valueOf(device.get().getOrDefault("deviceType", "")).toUpperCase();
        if (deviceType.contains("CAMERA") || deviceType.contains("HUB") || deviceType.contains("SENSOR")) {
            throw new IllegalArgumentException("Dieses Tapo-Geraet unterstuetzt Ein-/Ausschalten nicht: " + deviceType);
        }
    }

    private Optional<Map<String, Object>> findDeviceById(String deviceId) {
        return listDevices().stream()
                .filter(device -> deviceId.equals(device.get("deviceId")))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private void throwIfDeviceRequestFailed(Map<String, Object> response) {
        Object errCodeObj = response.get("error_code");
        if (errCodeObj instanceof Number errCode && errCode.intValue() != 0) {
            String errMsg = String.valueOf(response.getOrDefault("msg", "Unknown error"));
            throw new TapoException("Device operation failed: " + errMsg + " (code: " + errCode.intValue() + ")");
        }

        Object methodObj = response.get("method");
        if (methodObj instanceof Map<?, ?> methodResponseRaw) {
            Map<String, Object> methodResponse = (Map<String, Object>) methodResponseRaw;
            Object methodErrCodeObj = methodResponse.get("err_code");
            if (methodErrCodeObj instanceof Number methodErrCode && methodErrCode.intValue() != 0) {
                String methodErrMsg = String.valueOf(methodResponse.getOrDefault("err_msg", "Unknown error"));
                if (methodErrCode.intValue() == -2001 || methodErrMsg.contains("Module not support")) {
                    throw new TapoException("Device does not support this operation: " + methodErrMsg);
                }
                throw new TapoException("Device operation failed: " + methodErrMsg + " (code: " + methodErrCode.intValue() + ")");
            }
        }
    }

    @FunctionalInterface
    private interface CloudOperation<T> {
        T execute(TapoCloudSession session);
    }
}
