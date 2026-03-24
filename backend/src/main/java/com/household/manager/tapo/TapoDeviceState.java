package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;

public record TapoDeviceState(
        String nickname,
        String model,
        boolean poweredOn,
        boolean online
) {

    public static TapoDeviceState fromLocal(JsonNode deviceInfo, TapoCloudService tapoCloudService) {
        String nickname = tapoCloudService.decodeAlias(firstText(deviceInfo, "nickname", "alias"));
        String model = firstText(deviceInfo, "model", "device_model");
        boolean poweredOn = deviceInfo.path("device_on").asBoolean(false);
        return new TapoDeviceState(nickname, model, poweredOn, true);
    }

    public static TapoDeviceState from(
            JsonNode deviceInfo,
            TapoCloudDevice cloudDevice,
            TapoCloudService tapoCloudService
    ) {
        String nickname = tapoCloudService.decodeAlias(firstText(deviceInfo, "nickname", "alias"));
        if ((nickname == null || nickname.isBlank()) && cloudDevice != null) {
            nickname = tapoCloudService.decodeAlias(cloudDevice.alias());
        }

        String model = firstText(deviceInfo, "model", "device_model");
        if ((model == null || model.isBlank()) && cloudDevice != null) {
            model = cloudDevice.model();
        }

        boolean poweredOn = deviceInfo.path("device_on").asBoolean(false);
        // If we successfully got deviceInfo via passthrough, the device is online
        // regardless of the cloud status field (which is unreliable for Tapo devices)
        return new TapoDeviceState(nickname, model, poweredOn, true);
    }

    private static String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = node.path(fieldName).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
