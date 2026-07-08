package com.household.manager.alexa;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Konfiguration der Alexa-Integration. */
@Component
@ConfigurationProperties(prefix = "alexa")
@Getter
@Setter
public class AlexaProperties {

    /** Amazon-Domain des Kontos, z. B. amazon.de. */
    private String domain = "amazon.de";
}
