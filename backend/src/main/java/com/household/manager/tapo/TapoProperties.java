package com.household.manager.tapo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "tapo")
public class TapoProperties {

    private String email;
    private String password;
    private String cloudApiUrl = "https://wap.tplinkcloud.com";
    private long cloudTokenExpiryMs = 86_400_000L;
    private String localDiscoveryTarget = "255.255.255.255";
    private long localDiscoveryTimeoutMs = 4_000L;
    private List<TapoDeviceConfig> devices = new ArrayList<>();

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

    public String getCloudApiUrl() {
        return cloudApiUrl;
    }

    public void setCloudApiUrl(String cloudApiUrl) {
        this.cloudApiUrl = cloudApiUrl;
    }

    public long getCloudTokenExpiryMs() {
        return cloudTokenExpiryMs;
    }

    public void setCloudTokenExpiryMs(long cloudTokenExpiryMs) {
        this.cloudTokenExpiryMs = cloudTokenExpiryMs;
    }

    public String getLocalDiscoveryTarget() {
        return localDiscoveryTarget;
    }

    public void setLocalDiscoveryTarget(String localDiscoveryTarget) {
        this.localDiscoveryTarget = localDiscoveryTarget;
    }

    public long getLocalDiscoveryTimeoutMs() {
        return localDiscoveryTimeoutMs;
    }

    public void setLocalDiscoveryTimeoutMs(long localDiscoveryTimeoutMs) {
        this.localDiscoveryTimeoutMs = localDiscoveryTimeoutMs;
    }

    public List<TapoDeviceConfig> getDevices() {
        return devices;
    }

    public void setDevices(List<TapoDeviceConfig> devices) {
        this.devices = devices;
    }

    /**
     * Static device configuration for when UDP discovery doesn't work
     * (e.g., Docker, different subnet, firewall).
     */
    public static class TapoDeviceConfig {
        private String name;
        private String ip;
        private String deviceId;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    }
}
