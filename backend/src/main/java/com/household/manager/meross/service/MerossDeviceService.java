package com.household.manager.meross.service;

import com.household.manager.meross.config.MerossProperties;
import com.household.manager.meross.dto.MerossCloudDevice;
import com.household.manager.meross.dto.MerossCloudDevicesResponse;
import com.household.manager.meross.dto.MerossCloudLoginResponse;
import com.household.manager.meross.dto.MerossElectricityReading;
import com.household.manager.meross.dto.MerossPlugResponse;
import com.household.manager.meross.exception.MerossException;
import com.household.manager.meross.lib.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class MerossDeviceService {

    private static final int DEFAULT_CHANNEL = 0;

    private final MerossProperties merossProperties;
    private final MerossCloudAuthService merossCloudAuthService;

    /**
     * Geräte, die nachweislich keine Energiemessung können (MQTT-Antwort ohne die
     * Fähigkeit). Sie werden beim Sammellauf übersprungen, damit nicht für jedes
     * Licht und jede einfache Steckdose pro Zyklus eine MQTT-Verbindung entsteht.
     */
    private final Set<String> deviceIdsWithoutElectricity = ConcurrentHashMap.newKeySet();

    public List<MerossPlugResponse> discoverPlugs() {
        MerossCloudDevicesResponse response = merossCloudAuthService.listDevicesWithConfiguredCredentials();
        MerossCloudLoginResponse login = merossCloudAuthService.loginWithConfiguredCredentials();
        return response.devices().stream()
                .map(device -> toResponse(device, login))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    public MerossPlugResponse getStatus(String deviceId) {
        MerossCloudDevicesResponse response = merossCloudAuthService.listDevicesWithConfiguredCredentials();
        MerossCloudDevice cloudDevice = response.devices().stream()
                .filter(device -> deviceId.equals(device.uuid()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Meross-Geraet nicht gefunden: " + deviceId));

        MerossCloudLoginResponse login = merossCloudAuthService.loginWithConfiguredCredentials();
        return toResponse(cloudDevice, login);
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

    /**
     * Liest die Momentanwerte (Leistung/Spannung/Strom) einer Steckdose mit
     * Energiemessung über MQTT. Verbindung wird pro Messung auf- und abgebaut
     * (gleiches Muster wie beim Schalten).
     *
     * @throws MerossException wenn das Gerät nicht erreichbar ist, die Fähigkeit
     *                         fehlt oder die Antwort keinen Leistungswert enthält
     */
    public MerossElectricityReading readElectricity(String deviceId) {
        validateConfiguration();
        MerossCloudLoginResponse login = merossCloudAuthService.loginWithConfiguredCredentials();
        MerossCloudDevicesResponse devices = merossCloudAuthService.listDevicesWithConfiguredCredentials();
        MerossCloudDevice cloudDevice = devices.devices().stream()
                .filter(device -> deviceId.equals(device.uuid()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Meross-Geraet nicht gefunden: " + deviceId));

        MerossDevice device = buildMqttDevice(cloudDevice, login);
        try {
            Map payload = device.readElectricity();
            return MerossElectricityReading.fromPayload(deviceId, cloudDevice.devName(), payload)
                    .orElseThrow(() -> new MerossException(
                            "Meross-Elektrizitaetsantwort ohne Leistungswert (deviceId=" + deviceId + ")"));
        } catch (MerossException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new MerossException("Meross-Verbrauch konnte nicht gelesen werden: " + ex.getMessage(), ex);
        } finally {
            device.disconnect();
        }
    }

    /**
     * Liest den Momentanverbrauch aller messfähigen Steckdosen des Kontos.
     * <p>
     * Login und Geräteliste werden einmal geholt statt einmal pro Gerät — die
     * Cloud-Geräteliste ist ein ungecachter HTTP-Aufruf und würde sonst mit jedem
     * Poll-Zyklus vervielfacht. Offline-Geräte werden übersprungen (sie können
     * nicht antworten und würden nur in den MQTT-Timeout laufen), ebenso Geräte
     * ohne Energiemessung. Ein Fehler bei einem Gerät stoppt die übrigen nicht.
     */
    public List<MerossElectricityReading> readElectricityOfAllPlugs() {
        validateConfiguration();
        MerossCloudLoginResponse login = merossCloudAuthService.loginWithConfiguredCredentials();
        MerossCloudDevicesResponse devices = merossCloudAuthService.listDevicesWithConfiguredCredentials();

        List<MerossElectricityReading> readings = new ArrayList<>();
        for (MerossCloudDevice cloudDevice : devices.devices()) {
            if (!"online".equals(toOnlineStatus(cloudDevice.onlineStatus()))) {
                log.debug("Meross electricity skipped, device offline (deviceId={})", cloudDevice.uuid());
                continue;
            }
            if (deviceIdsWithoutElectricity.contains(cloudDevice.uuid())) {
                continue;
            }
            readElectricityQuietly(cloudDevice, login).ifPresent(readings::add);
        }
        return readings;
    }

    /**
     * @return leer, wenn das Gerät keine Energiemessung hat oder die Messung
     *         fehlschlägt; beides darf den Sammellauf nicht abbrechen
     */
    private Optional<MerossElectricityReading> readElectricityQuietly(
            MerossCloudDevice cloudDevice, MerossCloudLoginResponse login) {
        MerossDevice device = null;
        try {
            device = buildMqttDevice(cloudDevice, login);
            Map payload = device.readElectricity();
            if (payload == null) {
                // Kein Fehler, sondern eine endgültige Auskunft: dem Gerät fehlt die
                // Fähigkeit Appliance.Control.Electricity. Merken, damit nicht jeder
                // Zyklus erneut eine MQTT-Verbindung dafür aufbaut.
                deviceIdsWithoutElectricity.add(cloudDevice.uuid());
                log.info("Meross device has no electricity metering, skipping from now on (deviceId={}, name={})",
                        cloudDevice.uuid(), cloudDevice.devName());
                return Optional.empty();
            }
            return MerossElectricityReading.fromPayload(cloudDevice.uuid(), cloudDevice.devName(), payload);
        } catch (Throwable ex) {
            log.warn("Meross electricity read failed (deviceId={}): {}", cloudDevice.uuid(), ex.getMessage());
            return Optional.empty();
        } finally {
            if (device != null) {
                device.disconnect();
            }
        }
    }

    private MerossDevice buildMqttDevice(String deviceId) {
        validateConfiguration();
        MerossCloudLoginResponse login = merossCloudAuthService.loginWithConfiguredCredentials();
        MerossCloudDevicesResponse devices = merossCloudAuthService.listDevicesWithConfiguredCredentials();
        MerossCloudDevice cloudDevice = devices.devices().stream()
                .filter(device -> deviceId.equals(device.uuid()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Meross-Geraet nicht gefunden: " + deviceId));
        return buildMqttDevice(cloudDevice, login);
    }

    private MerossDevice buildMqttDevice(MerossCloudDevice cloudDevice, MerossCloudLoginResponse login) {
        validateConfiguration();
        String deviceId = cloudDevice.uuid();
        try {
            log.info("Meross MQTT build start (deviceId={})", deviceId);
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

    private void validateConfiguration() {
        if (merossProperties.getEmail() == null || merossProperties.getEmail().isBlank()) {
            throw new IllegalStateException("Meross ist nicht konfiguriert: 'meross.email' fehlt.");
        }
        if (merossProperties.getPassword() == null || merossProperties.getPassword().isBlank()) {
            throw new IllegalStateException("Meross ist nicht konfiguriert: 'meross.password' fehlt.");
        }
    }

    private MerossPlugResponse toResponse(MerossCloudDevice cloudDevice, MerossCloudLoginResponse login) {
        String onlineStatus = toOnlineStatus(cloudDevice.onlineStatus());
        // The cloud device list carries no toggle state. Read it over MQTT (the only path that
        // works against the device). Skip offline devices, which cannot answer and would only
        // block on the MQTT receive timeout.
        boolean on = "online".equals(onlineStatus) && readOnStateViaMqtt(cloudDevice, login);
        return new MerossPlugResponse(
                cloudDevice.uuid(),
                cloudDevice.devName(),
                cloudDevice.deviceType(),
                on,
                onlineStatus
        );
    }

    private boolean readOnStateViaMqtt(MerossCloudDevice cloudDevice, MerossCloudLoginResponse login) {
        MerossDevice device = null;
        try {
            device = buildMqttDevice(cloudDevice, login);
            return readFirstChannelState(device);
        } catch (Exception ex) {
            log.warn("Meross MQTT state read failed (deviceId={}): {}", cloudDevice.uuid(), ex.getMessage());
            return false;
        } finally {
            if (device != null) {
                device.disconnect();
            }
        }
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
