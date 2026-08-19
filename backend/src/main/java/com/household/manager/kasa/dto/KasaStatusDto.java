package com.household.manager.kasa.dto;

/**
 * @param relayState  on/off state; for a plug this is {@code relay_state}, for a bulb it is
 *                     {@code light_state.on_off} (see {@link com.household.manager.kasa.KasaSysInfoMapper}).
 * @param bulb         {@code true} for a bulb, {@code false} for a plug.
 * @param capabilities comma-separated, e.g. {@code "SWITCH,BRIGHTNESS,COLOR,COLOR_TEMP"}; see
 *                      {@link com.household.manager.kasa.KasaCapabilityMapper}.
 * @param brightness   1-100, {@code null} if the device reported none (e.g. a plug).
 * @param hue          0-360 degrees, {@code null} under the same conditions as {@link #brightness}.
 * @param saturation   0-100 percent, {@code null} under the same conditions as {@link #brightness}.
 * @param colorTemp    Kelvin, {@code null} under the same conditions as {@link #brightness}.
 */
public record KasaStatusDto(
        boolean relayState,
        String alias,
        String deviceId,
        boolean bulb,
        String capabilities,
        Integer brightness,
        Integer hue,
        Integer saturation,
        Integer colorTemp
) {
}
