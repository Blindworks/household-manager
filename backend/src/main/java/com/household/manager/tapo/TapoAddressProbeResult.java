package com.household.manager.tapo;

/**
 * Result of directly probing a Tapo device by IP address (see
 * {@link TapoDeviceService#probeAddress(String)}), bypassing discovery entirely.
 * <p>
 * Carries the protocol that actually worked alongside the resulting device state so the
 * caller can persist both without a second round trip to the device.
 */
public record TapoAddressProbeResult(TapoAuthProtocol protocol, TapoDeviceState state) {
}
