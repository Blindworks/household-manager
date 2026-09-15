package com.household.manager.zigbee.model;

import java.time.Duration;
import java.time.Instant;

/**
 * @param basis       worauf das Urteil beruht: {@code zigbee2mqtt}, {@code registry},
 *                    {@code last-message} oder null bei UNKNOWN
 * @param lastHeardAt letzte Geraetenachricht (Speicher oder DB), null wenn nie
 * @param silentFor   Zeit seit lastHeardAt, null wenn nie
 */
public record DeviceHealth(DeviceHealthStatus status, String basis, Instant lastHeardAt, Duration silentFor) {
}
