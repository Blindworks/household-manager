package com.household.manager.smartdevice;

/**
 * Desired (or, coming back from a device's own write-response, actually-applied) light state for
 * a light-capable smart device. Cross-platform value type shared by {@code tapo} and {@code kasa}
 * — it originally lived in the {@code tapo} package, but Kasa bulb support (see
 * {@code KasaService#setLightState}) made it a genuinely shared shape, so it moved here rather
 * than being duplicated per platform.
 * <p>
 * Every field is nullable and independent — only the fields that are actually set here get sent
 * to a device; a {@code null} field is omitted from the request rather than sent as a cleared
 * value. The same type is reused for the OPPOSITE direction too: {@code KasaService.setLightState}
 * returns a {@code LightState} parsed back out of the device's own response (see the class-level
 * caveat there about {@code err_code: 0} not proving a value landed), and {@code TapoDeviceState}
 * carries one for the same "what the device actually reports right now" purpose.
 * <p>
 * <b>Colour ({@code hue}/{@code saturation}) and colour-temperature ({@code colorTemp}) are
 * mutually exclusive modes on these bulbs and must never both be set on the same instance</b> when
 * used as a desired-state request. {@link com.household.manager.service.SmartDeviceService#setLightState}
 * rejects a request that sets both with a 400 <em>before</em> a {@code LightState} is even
 * constructed — silently favouring one over the other here would discard the caller's other value
 * without telling them, exactly the "silent ignore" the API contract forbids. By the time a
 * {@code LightState} reaches {@code TapoDeviceService#setLightState} or
 * {@code KasaService#setLightState}, at most one of the two groups is populated; both methods force
 * {@code color_temp: 0} onto the wire alongside {@code hue}/{@code saturation} (when the device
 * supports it) so a colour request actually switches the bulb out of white mode.
 *
 * @param brightness 1-100
 * @param hue        0-360 degrees
 * @param saturation 0-100 percent
 * @param colorTemp  Kelvin; valid range is device-specific (Tapo: {@code color_temp_range}; Kasa
 *                   has no known equivalent field, see {@code SmartDeviceService.resolveColorTempRange});
 *                   never set together with {@code hue}/{@code saturation}
 */
public record LightState(Integer brightness, Integer hue, Integer saturation, Integer colorTemp) {
}
