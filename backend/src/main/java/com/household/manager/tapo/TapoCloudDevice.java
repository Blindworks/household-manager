package com.household.manager.tapo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TapoCloudDevice(
        String alias,
        String status,
        String role,
        String model,
        String deviceId,
        String deviceType,
        String deviceName,
        String deviceHwVer,
        String deviceMac,
        String fwVer,
        String appServerUrl
) {

    public TapoCloudDevice(
            @JsonProperty("alias") String alias,
            @JsonProperty("status") String status,
            @JsonProperty("role") String role,
            @JsonProperty("deviceModel") String model,
            @JsonProperty("deviceId") String deviceId,
            @JsonProperty("deviceType") String deviceType,
            @JsonProperty("deviceName") String deviceName,
            @JsonProperty("deviceHwVer") String deviceHwVer,
            @JsonProperty("deviceMac") String deviceMac,
            @JsonProperty("fwVer") String fwVer,
            @JsonProperty("appServerUrl") String appServerUrl
    ) {
        this.alias = alias;
        this.status = status;
        this.role = role;
        this.model = model;
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.deviceName = deviceName;
        this.deviceHwVer = deviceHwVer;
        this.deviceMac = deviceMac;
        this.fwVer = fwVer;
        this.appServerUrl = appServerUrl;
    }
}
