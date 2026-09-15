package com.household.manager.zigbee.service;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.zigbee.model.ZigbeeBridgeDevice;
import com.household.manager.zigbee.model.ZigbeeBridgeInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Schreibende Geraeteverwaltung ueber die zigbee2mqtt-Bridge-API. Alle Aktionen ADMIN
 * (SecurityConfig); Audit erst NACH erfolgreicher Antwort (Muster NukiLockService).
 * <p>
 * Geraete werden ueber die IEEE-Adresse adressiert, nicht ueber den Friendly Name:
 * der ist genau das, was Umbenennen aendert, und z2m erlaubt '/' im Namen.
 */
@Service
@RequiredArgsConstructor
public class ZigbeeDeviceManagementService {

    static final int MAX_PERMIT_JOIN_SECONDS = 254;
    static final int MAX_NAME_LENGTH = 64;

    private final ZigbeeBridgeRequestService requestService;
    private final ZigbeeDeviceRegistry registry;
    private final AuditService auditService;

    public void permitJoin(int seconds) {
        if (seconds < 1 || seconds > MAX_PERMIT_JOIN_SECONDS) {
            throw new IllegalArgumentException("Anlerndauer muss zwischen 1 und 254 Sekunden liegen.");
        }
        requestService.send("permit_join", permitJoinParams(seconds));
        auditService.record("zigbee.permit-join.open", seconds + " s");
    }

    public void closePermitJoin() {
        requestService.send("permit_join", permitJoinParams(0));
        auditService.record("zigbee.permit-join.close", "");
    }

    /**
     * z2m 1.x verlangt {@code value: true/false} (+ optional time), 2.x nur {@code time}
     * (0 = schliessen). Die Version kommt aus bridge/info; ohne Info gilt 2.x.
     */
    private Map<String, Object> permitJoinParams(int seconds) {
        boolean legacy = registry.info().map(ZigbeeBridgeInfo::version)
                .map(version -> version.startsWith("1.")).orElse(false);
        return legacy
                ? Map.of("value", seconds > 0, "time", seconds)
                : Map.of("time", seconds);
    }

    public void rename(String ieeeAddress, String newName) {
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name muss 1 bis 64 Zeichen lang sein.");
        }
        if (trimmed.contains("/") || trimmed.contains("+") || trimmed.contains("#")) {
            throw new IllegalArgumentException("Name darf kein '/', '+' oder '#' enthalten (MQTT-Topic).");
        }
        ZigbeeBridgeDevice device = require(ieeeAddress);
        requestService.send("device/rename", Map.of("from", device.friendlyName(), "to", trimmed));
        auditService.record("zigbee.device.rename", device.friendlyName() + " -> " + trimmed);
    }

    public void interview(String ieeeAddress) {
        ZigbeeBridgeDevice device = require(ieeeAddress);
        requestService.send("device/interview", Map.of("id", device.ieeeAddress()));
        auditService.record("zigbee.device.interview", label(device));
    }

    public void configure(String ieeeAddress) {
        ZigbeeBridgeDevice device = require(ieeeAddress);
        requestService.send("device/configure", Map.of("id", device.ieeeAddress()));
        auditService.record("zigbee.device.configure", label(device));
    }

    /**
     * @param force nur den Eintrag in z2m loeschen, ohne das Geraet zu erreichen — fuer
     *              tote Batteriegeraete; auf ein lebendes Geraet angewendet funkt es weiter
     *              und taucht beim naechsten Anlernen wieder auf
     */
    public void remove(String ieeeAddress, boolean force) {
        ZigbeeBridgeDevice device = require(ieeeAddress);
        requestService.send("device/remove", Map.of("id", device.ieeeAddress(), "force", force));
        auditService.record("zigbee.device.remove", label(device) + ", force=" + force);
    }

    private ZigbeeBridgeDevice require(String ieeeAddress) {
        return registry.findByIeee(ieeeAddress)
                .orElseThrow(() -> new ResourceNotFoundException("Zigbee-Geraet", "ieeeAddress", ieeeAddress));
    }

    private static String label(ZigbeeBridgeDevice device) {
        return device.friendlyName() + " (" + device.ieeeAddress() + ")";
    }
}
