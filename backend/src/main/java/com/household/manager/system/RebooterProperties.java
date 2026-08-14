package com.household.manager.system;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Anbindung des Rebooter-Sidecars (Neustart aller Compose-Container).
 * Ohne URL und Token ist der Reboot deaktiviert — z. B. in der lokalen
 * Entwicklung ohne Docker.
 */
@Configuration
@ConfigurationProperties(prefix = "rebooter")
@Data
public class RebooterProperties {

    private String baseUrl = "";
    @ToString.Exclude
    private String token = "";
    private int timeoutMs = 5000;
}
