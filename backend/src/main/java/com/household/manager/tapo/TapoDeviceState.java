package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.household.manager.smartdevice.LightState;

/**
 * @param colorTempMin      device-reported lower bound of {@code color_temp_range} (Kelvin), or
 *                          {@code null} if the device didn't report one (e.g. no COLOR_TEMP
 *                          capability, or this state predates that field's introduction)
 * @param colorTempMax      device-reported upper bound of {@code color_temp_range} (Kelvin), or
 *                          {@code null} under the same conditions as {@link #colorTempMin}
 * @param currentLightState the device's current brightness/hue/saturation/colorTemp, read
 *                          straight off {@code get_device_info} (not the range, the live values) —
 *                          used to seed the frontend's light controls with the bulb's actual state
 *                          instead of an invented default. {@code null} if the device reported none
 *                          of those fields (e.g. a plain switch); individual fields inside it are
 *                          {@code null} if the device didn't report that specific one.
 */
public record TapoDeviceState(
        String nickname,
        String model,
        boolean poweredOn,
        boolean online,
        String capabilities,
        Integer colorTempMin,
        Integer colorTempMax,
        LightState currentLightState
) {

    /** Convenience constructor for callers that don't care about colour-temp range or current light state. */
    public TapoDeviceState(String nickname, String model, boolean poweredOn, boolean online, String capabilities) {
        this(nickname, model, poweredOn, online, capabilities, null, null, null);
    }

    public static TapoDeviceState fromLocal(JsonNode deviceInfo, TapoCloudService tapoCloudService) {
        String nickname = tapoCloudService.decodeAlias(firstText(deviceInfo, "nickname", "alias"));
        String model = firstText(deviceInfo, "model", "device_model");
        boolean poweredOn = deviceInfo.path("device_on").asBoolean(false);
        String capabilities = TapoCapabilityMapper.deriveCapabilities(deviceInfo);
        int[] colorTempRange = readColorTempRange(deviceInfo);
        return new TapoDeviceState(nickname, model, poweredOn, true, capabilities,
                colorTempRange == null ? null : colorTempRange[0],
                colorTempRange == null ? null : colorTempRange[1],
                readCurrentLightState(deviceInfo));
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
                colorTempRange == null ? null : colorTempRange[1],
                readCurrentLightState(deviceInfo));
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

    /**
     * Reads the device's current {@code brightness}/{@code hue}/{@code saturation}/
     * {@code color_temp} values (not the range — the live values reported alongside it), used to
     * seed the frontend's light controls with the bulb's actual state. Returns {@code null} if
     * none of the four fields are present (a plain switch); a field that IS present is read even
     * if its value is {@code 0} (e.g. {@code color_temp: 0} in pure colour mode is a real value,
     * not "absent" — same reasoning {@link TapoCapabilityMapper} already applies to capability
     * derivation).
     */
    private static LightState readCurrentLightState(JsonNode deviceInfo) {
        Integer brightness = deviceInfo.has("brightness") ? deviceInfo.path("brightness").asInt() : null;
        Integer hue = deviceInfo.has("hue") ? deviceInfo.path("hue").asInt() : null;
        Integer saturation = deviceInfo.has("saturation") ? deviceInfo.path("saturation").asInt() : null;
        Integer colorTemp = deviceInfo.has("color_temp") ? deviceInfo.path("color_temp").asInt() : null;
        if (brightness == null && hue == null && saturation == null && colorTemp == null) {
            return null;
        }
        return new LightState(brightness, hue, saturation, colorTemp);
    }
}
