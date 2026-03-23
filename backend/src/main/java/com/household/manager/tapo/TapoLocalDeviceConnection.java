package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;

public interface TapoLocalDeviceConnection {

    JsonNode getDeviceInfo();

    void setDevicePowered(boolean poweredOn);
}
