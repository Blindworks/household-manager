package com.household.manager.tapo.model;

public class TapoDevice {

    private String ip;
    private String deviceType;

    public TapoDevice() {
    }

    public TapoDevice(String ip, String deviceType) {
        this.ip = ip;
        this.deviceType = deviceType;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType;
    }
}
