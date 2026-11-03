package com.household.manager.dirigera;

import java.time.Instant;

/** Ein Tastendruck aus dem Ereignisstrom des Hubs; {@code time} ist null, wenn der Hub keine Zeit mitschickt. */
public record DirigeraRemotePress(String deviceId, String clickPattern, Instant time) {
}
