package com.household.manager.meross.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.meross.config.MerossProperties;
import com.household.manager.meross.dto.MerossCloudDevice;
import com.household.manager.meross.dto.MerossCloudDevicesResponse;
import com.household.manager.meross.dto.MerossCloudLoginResponse;
import com.household.manager.meross.dto.MerossPlugResponse;
import com.household.manager.meross.exception.MerossException;
import com.household.manager.meross.lib.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerossDeviceService {

    private static final int DEFAULT_CHANNEL = 0;

    private final MerossProperties merossProperties;
    private final MerossCloudAuthService merossCloudAuthService;

    public List<MerossPlugResponse> discoverPlugs() {
        MerossCloudDevicesResponse response = merossCloudAuthService.listDevicesWithConfiguredCredentials();
        return response.devices().stream()
                .map(this::toResponse)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    public MerossPlugResponse getStatus(String deviceId) {
        // Use Cloud API instead of MQTT to avoid library initialization issues
        MerossCloudDevicesResponse response = merossCloudAuthService.listDevicesWithConfiguredCredentials();
        MerossCloudDevice cloudDevice = response.devices().stream()
                .filter(device -> deviceId.equals(device.uuid()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Meross-Geraet nicht gefunden: " + deviceId));

        return toResponse(cloudDevice);
    }

    public void turnOn(String deviceId) {
        MerossDevice device = buildMqttDevice(deviceId);
        try {
            device.turnOnChannel(DEFAULT_CHANNEL);
            log.info("Meross device switched on (deviceId={})", deviceId);
        } catch (Throwable ex) {
            throw new MerossException("Meross-Steckdose konnte nicht eingeschaltet werden: " + ex.getMessage(), ex);
        }
    }

    public void turnOff(String deviceId) {
        MerossDevice device = buildMqttDevice(deviceId);
        try {
            device.turnOffChannel(DEFAULT_CHANNEL);
            log.info("Meross device switched off (deviceId={})", deviceId);
        } catch (Throwable ex) {
            throw new MerossException("Meross-Steckdose konnte nicht ausgeschaltet werden: " + ex.getMessage(), ex);
        }
    }

    private MerossDevice buildMqttDevice(String deviceId) {
        validateConfiguration();
        try {
            log.info("Meross MQTT build start (deviceId={})", deviceId);
            configureMerossLibraryObjectMappers();
            MerossCloudLoginResponse login = merossCloudAuthService.loginWithConfiguredCredentials();
            MerossCloudDevicesResponse devices = merossCloudAuthService.listDevicesWithConfiguredCredentials();
            MerossCloudDevice cloudDevice = devices.devices().stream()
                    .filter(device -> deviceId.equals(device.uuid()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Meross-Geraet nicht gefunden: " + deviceId));

            Device mqttDevice = new Device();
            mqttDevice.setUuid(cloudDevice.uuid());
            mqttDevice.setDevName(cloudDevice.devName());
            mqttDevice.setDeviceType(cloudDevice.deviceType());
            mqttDevice.setDomain(cloudDevice.domain());
            mqttDevice.setReservedDomain(cloudDevice.reservedDomain());

            long userId = Long.parseLong(login.userId());
            AttachedDevice attachedDevice = new AttachedDevice(mqttDevice, login.token(), login.key(), userId);

            String mqttDomain = pickMqttDomain(cloudDevice, login);
            log.info("Meross MQTT connect (deviceId={}, domain={})", deviceId, mqttDomain);
            MqttConnection connection = new MqttConnection(login.key(), userId, login.token(), mqttDomain);

            MerossDevice merossDevice = new MerossDevice(attachedDevice, connection);

            // Try to initialize, but continue even if it fails
            try {
                merossDevice.initialize();
                log.info("Meross MQTT initialized successfully (deviceId={})", deviceId);
            } catch (Exception initEx) {
                log.warn("Meross MQTT initialize failed (deviceId={}), continuing anyway: {}",
                        deviceId, initEx.getMessage());
            }

            log.info("Meross MQTT build success (deviceId={})", deviceId);
            return merossDevice;
        } catch (Exception ex) {
            log.warn("Meross MQTT build failed (deviceId={}): {}", deviceId, ex.getMessage());
            throw new MerossException("Meross-Geraet konnte nicht verbunden werden: " + ex.getMessage(), ex);
        }
    }

    private void configureMerossLibraryObjectMappers() {
        configureMapperForClass("com.scout24.ha.meross.mqtt.MqttConnection", "mapper");
        configureMapperForClass("com.scout24.ha.meross.mqtt.MerossDevice", "mapper");
    }

    private void configureMapperForClass(String className, String fieldName) {
        try {
            Class<?> targetClass = Class.forName(className);
            Field mapperField = targetClass.getDeclaredField(fieldName);
            mapperField.setAccessible(true);
            Object value = mapperField.get(null);
            if (value instanceof ObjectMapper mapper) {
                mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                log.debug("Configured {}.{} to ignore unknown JSON fields", className, fieldName);
            }
        } catch (ClassNotFoundException | NoSuchFieldException ignored) {
            // Best effort: keep running even if library internals change.
        } catch (Throwable ex) {
            log.debug("Could not configure mapper for {}.{}: {}", className, fieldName, ex.getMessage());
        }
    }

    private void validateConfiguration() {
        if (merossProperties.getEmail() == null || merossProperties.getEmail().isBlank()) {
            throw new IllegalStateException("Meross ist nicht konfiguriert: 'meross.email' fehlt.");
        }
        if (merossProperties.getPassword() == null || merossProperties.getPassword().isBlank()) {
            throw new IllegalStateException("Meross ist nicht konfiguriert: 'meross.password' fehlt.");
        }
    }

    private MerossPlugResponse toResponse(String deviceId, MerossDevice merossDevice) {
        AttachedDevice attachedDevice = readAttachedDevice(merossDevice);
        boolean on = readFirstChannelState(merossDevice);

        String name = attachedDevice != null && attachedDevice.getDevName() != null && !attachedDevice.getDevName().isBlank()
                ? attachedDevice.getDevName()
                : deviceId;
        String deviceType = attachedDevice != null && attachedDevice.getDeviceType() != null
                ? attachedDevice.getDeviceType()
                : "unknown";
        String onlineStatus = attachedDevice == null ? "unknown" : (attachedDevice.isOnline() ? "online" : "offline");

        return new MerossPlugResponse(deviceId, name, deviceType, on, onlineStatus);
    }

    private MerossPlugResponse toResponse(MerossCloudDevice cloudDevice) {
        String onlineStatus = toOnlineStatus(cloudDevice.onlineStatus());
        return new MerossPlugResponse(
                cloudDevice.uuid(),
                cloudDevice.devName(),
                cloudDevice.deviceType(),
                false,
                onlineStatus
        );
    }

    private AttachedDevice readAttachedDevice(MerossDevice merossDevice) {
        try {
            Field field = MerossDevice.class.getDeclaredField("device");
            field.setAccessible(true);
            Object value = field.get(merossDevice);
            if (value instanceof AttachedDevice attachedDevice) {
                return attachedDevice;
            }
        } catch (Throwable ex) {
            log.debug("Could not read Meross attached device metadata", ex);
        }
        return null;
    }

    private boolean readFirstChannelState(MerossDevice merossDevice) {
        try {
            Field stateField = MerossDevice.class.getDeclaredField("state");
            stateField.setAccessible(true);
            Object current = stateField.get(merossDevice);
            if (current instanceof boolean[] states && states.length > 0) {
                return states[0];
            }
        } catch (Throwable ex) {
            log.debug("Could not read Meross channel state", ex);
        }
        return false;
    }

    private static String toOnlineStatus(String value) {
        if (value == null) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(normalized) || "online".equals(normalized) || "true".equals(normalized)) {
            return "online";
        }
        if ("2".equals(normalized) || "offline".equals(normalized) || "false".equals(normalized)) {
            return "offline";
        }
        return "unknown";
    }

    private static String pickMqttDomain(MerossCloudDevice cloudDevice, MerossCloudLoginResponse login) {
        if (cloudDevice.reservedDomain() != null && !cloudDevice.reservedDomain().isBlank()) {
            return cloudDevice.reservedDomain();
        }
        if (cloudDevice.domain() != null && !cloudDevice.domain().isBlank()) {
            return cloudDevice.domain();
        }
        if (login.mqttDomain() != null && !login.mqttDomain().isBlank()) {
            return login.mqttDomain();
        }
        throw new MerossException("Kein MQTT-Domain fuer Meross-Geraet verfuegbar.");
    }
}
