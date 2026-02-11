package com.household.manager.kasa.dto;

public class KasaDiscoveryDto {

    private String ip;
    private String deviceId;
    private String model;
    private String alias;
    private boolean relayState;

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public boolean isRelayState() {
        return relayState;
    }

    public void setRelayState(boolean relayState) {
        this.relayState = relayState;
    }
}
