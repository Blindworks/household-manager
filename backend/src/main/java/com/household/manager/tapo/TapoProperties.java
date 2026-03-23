package com.household.manager.tapo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tapo")
public class TapoProperties {

    private String email;
    private String password;
    private String cloudApiUrl = "https://wap.tplinkcloud.com";
    private long cloudTokenExpiryMs = 86_400_000L;
    private String localDiscoveryTarget = "255.255.255.255";
    private long localDiscoveryTimeoutMs = 4_000L;

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
}
