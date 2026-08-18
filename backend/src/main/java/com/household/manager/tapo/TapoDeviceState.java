package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @param colorTempMin device-reported lower bound of {@code color_temp_range} (Kelvin), or
 *                      {@code null} if the device didn't report one (e.g. no COLOR_TEMP
 *                      capability, or this state predates that field's introduction)
 * @param colorTempMax device-reported upper bound of {@code color_temp_range} (Kelvin), or
 *                      {@code null} under the same conditions as {@link #colorTempMin}
 */
public record TapoDeviceState(
        String nickname,
        String model,
        boolean poweredOn,
        boolean online,
        String capabilities,
        Integer colorTempMin,
        Integer colorTempMax
) {

    /** Convenience constructor for callers that don't care about the colour-temp range. */
    public TapoDeviceState(String nickname, String model, boolean poweredOn, boolean online, String capabilities) {
        this(nickname, model, poweredOn, online, capabilities, null, null);
    }

    public static TapoDeviceState fromLocal(JsonNode deviceInfo, TapoCloudService tapoCloudService) {
        String nickname = tapoCloudService.decodeAlias(firstText(deviceInfo, "nickname", "alias"));
        String model = firstText(deviceInfo, "model", "device_model");
        boolean poweredOn = deviceInfo.path("device_on").asBoolean(false);
        String capabilities = TapoCapabilityMapper.deriveCapabilities(deviceInfo);
        int[] colorTempRange = readColorTempRange(deviceInfo);
        return new TapoDeviceState(nickname, model, poweredOn, true, capabilities,
                colorTempRange == null ? null : colorTempRange[0],
                colorTempRange == null ? null : colorTempRange[1]);
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
        String capabilities = TapoCapabilityMapper.deriveCapabilities(deviceInfo);
        int[] colorTempRange = readColorTempRange(deviceInfo);
        // If we successfully got deviceInfo via passthrough, the device is online
        // regardless of the cloud status field (which is unreliable for Tapo devices)
        return new TapoDeviceState(nickname, model, poweredOn, true, capabilities,
                colorTempRange == null ? null : colorTempRange[0],
                colorTempRange == null ? null : colorTempRange[1]);
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

    /**
     * Reads the device's own {@code color_temp_range: [min, max]} array (e.g. {@code [2500, 6500]}
     * on the L530), used to validate a colour-temperature request against the device's actual
     * range instead of a hardcoded default. Returns {@code null} if the field is absent or not a
     * plausible two-element array — a malformed/missing field must not crash capability derivation
     * or persistence, callers fall back to a sane default range instead.
     */
    private static int[] readColorTempRange(JsonNode deviceInfo) {
        JsonNode range = deviceInfo.path("color_temp_range");
        if (!range.isArray() || range.size() != 2) {
            return null;
        }
        int min = range.get(0).asInt(-1);
        int max = range.get(1).asInt(-1);
        if (min < 0 || max < 0 || min >= max) {
            return null;
        }
        return new int[]{min, max};
    }
}
