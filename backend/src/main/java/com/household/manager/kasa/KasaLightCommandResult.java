package com.household.manager.kasa;

import com.household.manager.smartdevice.LightState;

/**
 * What {@link KasaService#setLightState} actually got back from the device after sending a
 * {@code transition_light_state} command — the device's own report of the resulting state, not
 * an echo of the request. See {@link KasaService#setLightState} for the measured evidence on why
 * a successful ({@code err_code: 0}) response does not by itself prove the requested values were
 * applied.
 *
 * @param poweredOn the resulting {@code on_off} state
 * @param lightState the resulting brightness/hue/saturation/colorTemp, as reported
 */
public record KasaLightCommandResult(boolean poweredOn, LightState lightState) {
}
