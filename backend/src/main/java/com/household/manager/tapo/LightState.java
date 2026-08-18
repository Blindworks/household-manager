package com.household.manager.tapo;

/**
 * Desired light state to apply to a Tapo bulb via {@code set_device_info}. Every field is
 * nullable and independent — only the fields that are actually set here get sent to the
 * device; a {@code null} field is omitted from the request rather than sent as a cleared value.
 * <p>
 * <b>Colour ({@code hue}/{@code saturation}) and colour-temperature ({@code colorTemp}) are
 * mutually exclusive modes on these bulbs and must never both be set on the same instance.</b>
 * {@link com.household.manager.service.SmartDeviceService#setLightState} rejects a request that
 * sets both with a 400 <em>before</em> a {@code LightState} is even constructed — silently
 * favouring one over the other here would discard the caller's other value without telling them,
 * exactly the "silent ignore" the API contract forbids. By the time a {@code LightState} reaches
 * {@link TapoDeviceService#setLightState}, at most one of the two groups is populated; that method
 * forces {@code color_temp: 0} onto the wire alongside {@code hue}/{@code saturation} (when the
 * device supports it) so a colour request actually switches the bulb out of white mode.
 *
 * @param brightness 1-100
 * @param hue        0-360 degrees
 * @param saturation 0-100 percent
 * @param colorTemp  Kelvin; valid range is device-specific (see {@code color_temp_range}); never
 *                   set together with {@code hue}/{@code saturation}
 */
public record LightState(Integer brightness, Integer hue, Integer saturation, Integer colorTemp) {
}
