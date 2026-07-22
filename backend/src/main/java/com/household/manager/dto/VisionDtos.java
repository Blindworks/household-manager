package com.household.manager.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** DTOs der Vision-REST-API (Personen, Erkennungen, Login, Sidecar-Webhook). */
public final class VisionDtos {

    private VisionDtos() {
    }

    public record PersonRequest(String name, Boolean active) {}

    public record PersonResponse(Long id, String name, boolean active, int photoCount) {}

    public record PhotoResponse(Long id, Long personId, String photoBase64) {}

    public record RecognitionResponse(Long id, LocalDateTime recognizedAt, Long personId, String personName,
                                      BigDecimal confidence, int unknownFaces, String thumbnailBase64) {}

    public record LoginRequest(String username, String password) {}

    public record VerifyRequest(String code) {}

    public record StatusResponse(boolean sidecarReachable, boolean loggedIn, boolean cameraFound,
                                 String cameraName, String lastPollAt) {}

    /** Webhook-Payload des Sidecars. */
    public record RecognitionWebhook(List<WebhookPerson> persons, Integer unknownFaces, String thumbnailBase64) {}

    public record WebhookPerson(Long personId, String name, BigDecimal confidence) {}

    /** Embedding-Liste fuer den Sidecar-Start. */
    public record EmbeddingsPerson(Long personId, String name, List<float[]> embeddings) {}
}
