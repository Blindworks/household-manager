package com.household.manager.tapo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "tapo")
public class TapoProperties {

    private String email;
    private String password;
    private int discoveryTimeoutMs = 3000;
    private List<String> devices = new ArrayList<>();

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getDiscoveryTimeoutMs() {
        return discoveryTimeoutMs;
    }

    public void setDiscoveryTimeoutMs(int discoveryTimeoutMs) {
        this.discoveryTimeoutMs = discoveryTimeoutMs;
    }

    public List<String> getDevices() {
        return devices;
    }

    public void setDevices(List<String> devices) {
        this.devices = devices != null ? devices : new ArrayList<>();
    }
}
