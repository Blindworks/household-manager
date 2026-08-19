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
 */
public final class KasaCapabilityMapper {

    private KasaCapabilityMapper() {
    }

    public static String deriveCapabilities(JsonNode sysInfo) {
        StringBuilder capabilities = new StringBuilder("SWITCH");
        if (sysInfo == null) {
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
