package com.household.manager.tapo;

/**
 * Result of directly probing a Tapo device by IP address (see
 * {@link TapoDeviceService#probeAddress(String)}), bypassing discovery entirely.
 * <p>
 * Carries the protocol that actually worked and the device's own {@code device_id} alongside the
 * resulting state so the caller can persist all three without a second round trip to the device.
 * <p>
 * {@code deviceId} matters beyond bookkeeping: it is the caller's only way to confirm that the IP
 * it just probed actually belongs to the device being edited. Nine near-identical Tapo devices on
 * one LAN make a mistyped-but-still-valid IP entirely plausible — without this check, such a typo
 * would silently rewrite one device's row with another physical device's identity.
 */
public record TapoAddressProbeResult(String deviceId, TapoAuthProtocol protocol, TapoDeviceState state) {
}
