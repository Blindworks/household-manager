package com.household.manager.zigbee.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Stille-Schwellen je Stromquelle fuer das Urteil je Geraet.
 * <p>
 * 25 h ist zigbee2mqtt's eigener Default fuer passive (Batterie-)Geraete, nicht am
 * eigenen Geraetepark verifiziert — nach einigen Tagen Betrieb nachziehen. Die
 * Netz-Schwelle entspricht der Watchdog-Schwelle {@code zigbee.watchdog.stale-after-minutes}
 * (der Default in application.properties referenziert dieselbe Env-Variable).
 */
@Component
@ConfigurationProperties(prefix = "zigbee.device-health")
@Getter
@Setter
public class ZigbeeDeviceHealthProperties {

    private int batterySilentAfterHours = 25;
    private int mainsSilentAfterMinutes = 15;

    public Duration batterySilentAfter() {
        return Duration.ofHours(batterySilentAfterHours);
    }

    public Duration mainsSilentAfter() {
        return Duration.ofMinutes(mainsSilentAfterMinutes);
    }
}
