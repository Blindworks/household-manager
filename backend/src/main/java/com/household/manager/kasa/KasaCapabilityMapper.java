package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Derives a Kasa device's capabilities from its own {@code system.get_sysinfo} response.
 * <p>
 * Unlike {@link com.household.manager.tapo.TapoCapabilityMapper}, which has to infer a Tapo
 * device's capabilities from which light-control fields happen to be present, Kasa devices state
 * their capabilities EXPLICITLY via {@code is_dimmable}/{@code is_color}/
 * {@code is_variable_color_temp} flags (1/0) - simpler and less fragile than the Tapo path, so
 * there is no field-presence guessing here.
 * <p>
 * The result is a comma-separated, order-stable string matching the existing {@code capabilities}
 * column format, using the SAME fixed order as {@code TapoCapabilityMapper}
 * ({@code "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"}) so two devices with identical capabilities produce
 * an identical string regardless of platform.
 * <p>
 * <b>{@code is_dimmable} alone is not enough:</b> a Kasa WALL DIMMER (HS220/KS220/KP405) reports
 * {@code is_dimmable: 1} but has no {@code light_state} node at all — it is a switch-with-a-dimmer,
 * not a bulb, and is dimmed via a completely different (and here unimplemented) protocol path than
 * {@code smartlife.iot.smartbulb.lightingservice}, which only bulbs speak. Without gating on
 * {@code light_state} presence too, such a device would get a BRIGHTNESS capability — and therefore
 * a frontend slider — that could only ever fail when used. This is belt-and-suspenders alongside
 * {@code SmartDeviceService.isKasaBulb}, which independently blocks the write path itself for any
 * non-bulb Kasa device with the same 400 a Meross device gets.
 */
public final class KasaCapabilityMapper {

    private KasaCapabilityMapper() {
    }

    public static String deriveCapabilities(JsonNode sysInfo) {
        StringBuilder capabilities = new StringBuilder("SWITCH");
        if (sysInfo == null || !sysInfo.path("light_state").isObject()) {
            return capabilities.toString();
        }

        if (sysInfo.path("is_dimmable").asInt(0) == 1) {
            capabilities.append(",BRIGHTNESS");
        }
        if (sysInfo.path("is_color").asInt(0) == 1) {
            capabilities.append(",COLOR");
        }
        if (sysInfo.path("is_variable_color_temp").asInt(0) == 1) {
            capabilities.append(",COLOR_TEMP");
        }

        return capabilities.toString();
    }
}
