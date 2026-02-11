package com.household.manager.tapo.service;

import com.household.manager.tapo.config.TapoProperties;
import com.household.manager.tapo.exception.TapoConnectionException;
import com.household.manager.tapo.exception.TapoException;
import com.household.manager.tapo.protocol.TapoProtocolClient;
import com.household.manager.tapo.protocol.TapoSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class TapoDeviceService {

    private final TapoProtocolClient tapoProtocolClient;
    private final TapoProperties tapoProperties;
    private final ConcurrentMap<String, CachedSession> sessionCache = new ConcurrentHashMap<>();

    public void connect(String deviceIp) {
        sessionCache.put(deviceIp, createSession(deviceIp));
    }

    public void turnOn(String deviceIp) {
        withReconnect(deviceIp, cached -> {
            send(cached, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("device_on", true)
            ));
            return null;
        });
    }

    public void turnOff(String deviceIp) {
        withReconnect(deviceIp, cached -> {
            send(cached, Map.of(
                    "method", "set_device_info",
                    "params", Map.of("device_on", false)
            ));
            return null;
        });
    }

    public Map<String, Object> getDeviceInfo(String deviceIp) {
        return withReconnect(deviceIp, cached -> send(cached, Map.of("method", "get_device_info")));
    }

    public Map<String, Object> getEnergyUsage(String deviceIp) {
        return withReconnect(deviceIp, cached -> send(cached, Map.of("method", "get_energy_usage")));
    }

    public void setBrightness(String deviceIp, int brightness) {
        if (brightness < 1 || brightness > 100) {
            throw new IllegalArgumentException("brightness must be between 1 and 100");
        }

        withReconnect(deviceIp, cached -> {
            send(cached, Map.of(
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

        withReconnect(deviceIp, cached -> {
            send(cached, Map.of(
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

        withReconnect(deviceIp, cached -> {
            send(cached, Map.of(
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

    private Map<String, Object> send(CachedSession cached, Map<String, Object> payload) {
        return tapoProtocolClient.sendRequest(cached.session(), cached.token(), payload);
    }

    private CachedSession getOrCreateSession(String deviceIp) {
        return sessionCache.computeIfAbsent(deviceIp, this::createSession);
    }

    private CachedSession createSession(String deviceIp) {
        String email = tapoProperties.getEmail();
        String password = tapoProperties.getPassword();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new TapoConnectionException("Tapo credentials are not configured (tapo.email / tapo.password)");
        }

        log.info("Opening Tapo session for {}", deviceIp);
        TapoSession session = tapoProtocolClient.handshake(deviceIp);
        String token = tapoProtocolClient.login(session, email, password);
        return new CachedSession(session, token);
    }

    private <T> T withReconnect(String deviceIp, TapoOperation<T> operation) {
        CachedSession cached = getOrCreateSession(deviceIp);
        try {
            return operation.execute(cached);
        } catch (TapoException ex) {
            log.warn("Tapo request failed for {}, reconnecting once: {}", deviceIp, ex.getMessage());
            CachedSession refreshed = createSession(deviceIp);
            sessionCache.put(deviceIp, refreshed);
            return operation.execute(refreshed);
        }
    }

    private record CachedSession(TapoSession session, String token) {
    }

    @FunctionalInterface
    private interface TapoOperation<T> {
        T execute(CachedSession cachedSession);
    }
}
