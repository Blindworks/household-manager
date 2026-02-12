package com.household.manager.tapo.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * DTO for Tapo device information.
 */
@Getter
@Setter
public class TapoDeviceInfoDto {

    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("type")
    private String type;

    @JsonProperty("model")
    private String model;

    @JsonProperty("hw_ver")
    private String hwVer;

    @JsonProperty("fw_ver")
    private String fwVer;

    @JsonProperty("mac")
    private String mac;

    @JsonProperty("nickname")
    @JsonIgnore
    private String nicknameEncoded;

    @JsonProperty("device_on")
    private Boolean deviceOn;

    @JsonProperty("on_time")
    private Integer onTime;

    @JsonProperty("signal_level")
    private Integer signalLevel;

    @JsonProperty("rssi")
    private Integer rssi;

    @JsonProperty("ip")
    private String ip;

    @JsonProperty("ssid")
    @JsonIgnore
    private String ssidEncoded;

    @JsonProperty("overheated")
    private Boolean overheated;

    /**
     * Get decoded nickname (device name).
     * Tapo devices return the nickname base64-encoded.
     */
    public String getNickname() {
        if (nicknameEncoded == null || nicknameEncoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(nicknameEncoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return nicknameEncoded; // Return as-is if decoding fails
        }
    }

    /**
     * Get decoded SSID.
     */
    public String getSsid() {
        if (ssidEncoded == null || ssidEncoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ssidEncoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return ssidEncoded;
        }
    }
}
