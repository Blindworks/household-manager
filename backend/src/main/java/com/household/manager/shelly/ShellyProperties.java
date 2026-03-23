package com.household.manager.shelly;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "shelly")
@Data
public class ShellyProperties {

    private List<ShellyDeviceConfig> devices = new ArrayList<>();
    private long pollingIntervalMs = 60000;
    private int httpTimeoutMs = 3000;

    @Data
    public static class ShellyDeviceConfig {
        private String name;
        private String ip;
    }
}
