package com.household.manager.laundry;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Deploy-Konfiguration der Waeschetrocknung: Schalter und die beobachteten Entitaeten. */
@Configuration
@ConfigurationProperties(prefix = "laundry")
@Data
public class LaundryProperties {

    private boolean enabled = true;
    private String humidityEntity = "sensor.zigbee_temperatur_aqara_keller_humidity";
    private String temperatureEntity = "sensor.zigbee_temperatur_aqara_keller_temperature";
    private String switchEntity = "switch.meross_2112156531504590863548e1e9817420";
    private String finishedHelper = "input_boolean.manual_waschmaschine_fertig";
    private String runningHelper = "input_boolean.manual_waschmaschine_laeuft";
}
