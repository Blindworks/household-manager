package com.household.manager.kasa.dto;

public class KasaDiscoveryDto {

    private String ip;
    private String deviceId;
    private String model;
    private String alias;
    private boolean relayState;
    /** {@code true} for a bulb ({@code light_state} present in its sysinfo), {@code false} for a plug. */
    private boolean bulb;
    /** Comma-separated, e.g. {@code "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"}; see {@link com.household.manager.kasa.KasaCapabilityMapper}. */
    private String capabilities;
    /** 1-100, {@code null} if the device reported none (e.g. a plug). */
    private Integer brightness;
    /** 0-360 degrees, {@code null} under the same conditions as {@link #brightness}. */
    private Integer hue;
    /** 0-100 percent, {@code null} under the same conditions as {@link #brightness}. */
    private Integer saturation;
    /** Kelvin, {@code null} under the same conditions as {@link #brightness}. */
    private Integer colorTemp;

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

    public boolean isBulb() {
        return bulb;
    }

    public void setBulb(boolean bulb) {
        this.bulb = bulb;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public Integer getBrightness() {
        return brightness;
    }

    public void setBrightness(Integer brightness) {
        this.brightness = brightness;
    }

    public Integer getHue() {
        return hue;
    }

    public void setHue(Integer hue) {
        this.hue = hue;
    }

    public Integer getSaturation() {
        return saturation;
    }

    public void setSaturation(Integer saturation) {
        this.saturation = saturation;
    }

    public Integer getColorTemp() {
        return colorTemp;
    }

    public void setColorTemp(Integer colorTemp) {
        this.colorTemp = colorTemp;
    }
}
