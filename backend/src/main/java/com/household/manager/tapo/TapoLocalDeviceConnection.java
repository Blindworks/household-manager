package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface TapoLocalDeviceConnection {

    JsonNode getDeviceInfo();

    void setDevicePowered(boolean poweredOn);

    /**
     * Issues a {@code set_device_info} request with an arbitrary params object, e.g. for
     * brightness/hue/saturation/color_temp light-state changes. Unlike {@link #setDevicePowered},
     * the caller builds the full params so it can send only the fields it actually wants to
     * change (see {@link TapoDeviceService#setLightState}).
     */
    void setDeviceInfo(ObjectNode params);

    JsonNode getEnergyUsage();

    JsonNode getCurrentPower();
}
