package com.household.manager.zigbee.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "zigbee.mqtt")
@Getter
@Setter
public class ZigbeeMqttProperties {
    private boolean enabled = true;
    private String host = "localhost";
    private int port = 1883;
    private String username = "";
    private String password = "";
    private String topicFilter = "zigbee2mqtt/#";
    private String clientId = "household-manager-zigbee";
}
