package com.household.manager.tapo;

/**
 * Desired light state to apply to a Tapo bulb via {@code set_device_info}. Every field is
 * nullable and independent — only the fields that are actually set here get sent to the
 * device; a {@code null} field is omitted from the request rather than sent as a cleared value.
 * <p>
 * See {@link TapoDeviceService#setLightState} for the colour vs. colour-temperature mode
 * handling: setting {@code hue}/{@code saturation} forces {@code color_temp} to {@code 0} in
 * the outgoing request regardless of what is passed here, so a caller that wants a pure
 * colour-temperature change must leave {@code hue} and {@code saturation} {@code null}.
 *
 * @param brightness 1-100
 * @param hue        0-360 degrees
 * @param saturation 0-100 percent
 * @param colorTemp  Kelvin; valid range is device-specific (see {@code color_temp_range})
 */
public record LightState(Integer brightness, Integer hue, Integer saturation, Integer colorTemp) {
}
