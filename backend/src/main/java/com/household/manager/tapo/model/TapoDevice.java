package com.household.manager.tapo.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TapoDevice {

    private String deviceId;
    private String deviceName;
    private String deviceType;
    private String model;
    private String ip;
    private boolean online;
    private String fwVer;

    public TapoDevice(String ip, String deviceType) {
        this.ip = ip;
        this.deviceType = deviceType;
    }
}
