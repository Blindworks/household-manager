package com.household.manager.push;

import java.time.LocalDateTime;

/** API-Vertraege der Push-Endpunkte. */
public final class PushDtos {

    private PushDtos() {
    }

    public record PublicKeyResponse(String publicKey) {}

    public record SubscribeRequest(String endpoint, String p256dh, String auth, String userAgent) {}

    /** endpoint ist enthalten, damit das Frontend "dieses Geraet" per Vergleich erkennen kann. */
    public record SubscriptionResponse(Long id, String deviceLabel, LocalDateTime createdAt,
                                       LocalDateTime lastUsedAt, String endpoint) {}
}
