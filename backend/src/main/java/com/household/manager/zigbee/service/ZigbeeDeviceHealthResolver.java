package com.household.manager.zigbee.service;

import com.household.manager.zigbee.config.ZigbeeDeviceHealthProperties;
import com.household.manager.zigbee.model.DeviceHealth;
import com.household.manager.zigbee.model.DeviceHealthStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Die einzige Definition von "lebt dieses Zigbee-Geraet" (Muster TractiveHomeResolver):
 * Seite und ein kuenftiger Flow-Trigger fragen dieselbe Klasse.
 * <p>
 * Reihenfolge der Regeln ist Teil des Vertrags: ein explizites offline von zigbee2mqtt
 * schlaegt eine frische Nachricht (z2m weiss ueber Ping und Funkstille mehr als wir),
 * ein unfertiges Interview schlaegt die Stille-Uhr, und "nie gehoert" wird als UNKNOWN
 * ausgewiesen statt als ACTIVE geraten. Wirft nie.
 */
@Component
public class ZigbeeDeviceHealthResolver {

    /**
     * @param bridgeAvailability z2m's Urteil aus name/availability, null wenn nie eines kam
     *                           (auch wenn die Pruefung im Add-on aus ist)
     * @param interviewCompleted aus dem Registry; null wenn das Geraet dort nicht steht
     * @param battery            Stromquelle Batterie (Registry) — ohne Registry-Eintrag true,
     *                           die konservativere Schwelle
     * @param lastHeardAt        letzte Geraetenachricht, Maximum aus Speicher und DB; null wenn nie
     */
    public record Input(Boolean bridgeAvailability, Boolean interviewCompleted, boolean battery, Instant lastHeardAt) {
    }

    private final ZigbeeDeviceHealthProperties properties;
    private final Clock clock;

    public ZigbeeDeviceHealthResolver(ZigbeeDeviceHealthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public DeviceHealth resolve(Input input) {
        Instant lastHeard = input.lastHeardAt();
        Duration silentFor = null;
        if (lastHeard != null) {
            Duration raw = Duration.between(lastHeard, clock.instant());
            // Uhrenversatz zwischen DB-Zeit und Server: nie eine negative Stille ausweisen
            silentFor = raw.isNegative() ? Duration.ZERO : raw;
        }

        if (Boolean.FALSE.equals(input.bridgeAvailability())) {
            return new DeviceHealth(DeviceHealthStatus.OFFLINE, "zigbee2mqtt", lastHeard, silentFor);
        }
        if (Boolean.FALSE.equals(input.interviewCompleted())) {
            return new DeviceHealth(DeviceHealthStatus.INTERVIEW, "registry", lastHeard, silentFor);
        }
        if (lastHeard == null) {
            return new DeviceHealth(DeviceHealthStatus.UNKNOWN, null, null, null);
        }
        Duration threshold = input.battery() ? properties.batterySilentAfter() : properties.mainsSilentAfter();
        DeviceHealthStatus status = silentFor.compareTo(threshold) >= 0
                ? DeviceHealthStatus.SILENT : DeviceHealthStatus.ACTIVE;
        return new DeviceHealth(status, "last-message", lastHeard, silentFor);
    }
}
