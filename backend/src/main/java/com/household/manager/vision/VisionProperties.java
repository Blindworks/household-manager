package com.household.manager.vision;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Konfiguration der Blink-Gesichtserkennung (blink-vision-Sidecar). */
@Component
@ConfigurationProperties(prefix = "vision")
@Getter
@Setter
public class VisionProperties {

    /** Integration aktiv (Heartbeat-Ueberwachung, Sidecar-Aufrufe). */
    private boolean enabled = true;

    /** Basis-URL des blink-vision-Sidecars. */
    private String sidecarBaseUrl = "http://localhost:8090";

    /** Nach so vielen Sekunden ohne Heartbeat gilt der Sidecar als offline. */
    private long heartbeatTimeoutSeconds = 180;
}
