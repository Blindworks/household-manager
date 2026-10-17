package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.DeviceType;
import com.household.manager.model.entity.SmartDevice;
import com.household.manager.repository.SmartDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pollt den Momentanverbrauch der messfähigen Tapo-Steckdosen (P110 & Co.) und spiegelt
 * ihn als {@code sensor.tapo_<deviceId>_power} in die Entity-State-Schicht — Gegenstück
 * zum {@code MerossElectricityPollingService}. Damit erscheinen sie in der
 * Verbraucher-Kachel, bekommen eine Leistungshistorie und zeigen Watt in der Schalterzeile.
 * <p>
 * Gelesen wird {@code get_current_power} (Watt); {@code current_power} aus
 * {@code get_energy_usage} wäre dagegen in Milliwatt.
 * <p>
 * Welche Modelle messen, steht in {@link #METERING_MODEL_PREFIXES}: Tapo meldet die
 * Messfähigkeit in {@code get_device_info} nicht, und ein Probeaufruf an eine Lampe
 * kostete jede Minute einen Protokoll-Fallback.
 */
@Service
@Slf4j
public class TapoPowerPollingService {

    static final List<String> METERING_MODEL_PREFIXES = List.of("P110", "P115", "P125");

    private final SmartDeviceRepository smartDeviceRepository;
    private final TapoDeviceService tapoDeviceService;
    private final EntityStateService entityStateService;
    private final boolean enabled;

    public TapoPowerPollingService(
            SmartDeviceRepository smartDeviceRepository,
            TapoDeviceService tapoDeviceService,
            EntityStateService entityStateService,
            @Value("${tapo.power-polling.enabled:true}") boolean enabled) {
        this.smartDeviceRepository = smartDeviceRepository;
        this.tapoDeviceService = tapoDeviceService;
        this.entityStateService = entityStateService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${tapo.power-polling.interval-ms:60000}",
            initialDelayString = "${tapo.power-polling.initial-delay-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        List<SmartDevice> devices;
        try {
            devices = smartDeviceRepository.findByDeviceTypeOrderByDeviceNameAsc(DeviceType.TAPO);
        } catch (Exception ex) {
            log.warn("Tapo-Leistungs-Poll: Geraeteliste nicht lesbar: {}", ex.getMessage());
            return;
        }
        for (SmartDevice device : devices) {
            if (isPollable(device)) {
                pollQuietly(device);
            }
        }
    }

    static boolean isMeteringModel(String model) {
        if (model == null) {
            return false;
        }
        String normalized = model.trim().toUpperCase(Locale.ROOT);
        return METERING_MODEL_PREFIXES.stream().anyMatch(normalized::startsWith);
    }

    private boolean isPollable(SmartDevice device) {
        return device.isOnline()
                && device.getIpAddress() != null
                && !device.getIpAddress().isBlank()
                && isMeteringModel(device.getModel());
    }

    private void pollQuietly(SmartDevice device) {
        try {
            JsonNode result = tapoDeviceService.getCurrentPower(
                    device.getExternalDeviceId(), device.getIpAddress(), null);
            JsonNode power = result == null ? null : result.get("current_power");
            if (power == null || !power.isNumber()) {
                log.debug("Tapo {} lieferte kein current_power: {}", device.getDeviceName(), result);
                return;
            }
            reportPowerState(device, power.decimalValue());
        } catch (Exception ex) {
            // Wie bei Meross bewusst kein 'unavailable': ein transienter Lesefehler soll
            // keinen Zustandsübergang erzeugen, sonst feuern flankengetriggerte Flows fehl.
            log.debug("Tapo-Leistungs-Poll fuer {} fehlgeschlagen: {}", device.getDeviceName(), ex.getMessage());
        }
    }

    private void reportPowerState(SmartDevice device, BigDecimal watts) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.SENSOR, EntitySource.TAPO,
                        device.getExternalDeviceId(), "power"))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.TAPO)
                .sourceRef(device.getExternalDeviceId())
                .friendlyName(device.getDeviceName() + " Leistung")
                .state(watts.stripTrailingZeros().toPlainString())
                .attributes(Map.of("unit", "W", "deviceClass", "power"))
                .build());
    }
}
