package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Derives a device's capabilities from its own {@code get_device_info} response instead of a
 * hardcoded value or a model lookup table (which would go stale with every new device TP-Link
 * ships). Every device is at least {@code SWITCH}; additional capabilities are added based on
 * which light-control fields the device's self-reported info actually contains.
 * <p>
 * The result is a comma-separated, order-stable string matching the existing {@code capabilities}
 * column format (e.g. {@code "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"}). Stability matters: an
 * unstable order would rewrite the DB column on every scan and fire pointless entity-state
 * change events even though nothing about the device actually changed.
 */
public final class TapoCapabilityMapper {

    private TapoCapabilityMapper() {
    }

    public static String deriveCapabilities(JsonNode deviceInfo) {
        StringBuilder capabilities = new StringBuilder("SWITCH");
        if (deviceInfo == null) {
            return capabilities.toString();
        }

        if (deviceInfo.has("brightness")) {
            capabilities.append(",BRIGHTNESS");
        }
        if (deviceInfo.has("hue") && deviceInfo.has("saturation")) {
            capabilities.append(",COLOR");
        }
        // Presence of the field indicates the capability, NOT its value. A colour-capable bulb
        // (e.g. the L530) reports color_temp: 0 while in pure colour mode - treating 0 as
        // "absent" would make the bulb lose COLOR_TEMP the moment a user picks a colour.
        if (deviceInfo.has("color_temp") || deviceInfo.has("color_temp_range")) {
            capabilities.append(",COLOR_TEMP");
        }

        return capabilities.toString();
    }
}
