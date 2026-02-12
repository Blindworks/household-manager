package com.household.manager.tapo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tapo")
public class TapoProperties {

    private String email;
    private String password;
    private String cloudApiUrl = "https://wap.tplinkcloud.com";
    private long cloudTokenExpiryMs = 86400000; // 24 hours

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
}
