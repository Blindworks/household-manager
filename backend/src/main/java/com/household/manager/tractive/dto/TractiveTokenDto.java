package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Antwort von {@code POST /auth/token}. {@code expiresAt} ist eine Unix-Sekunde. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractiveTokenDto(
        @JsonProperty("user_id") String userId,
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_at") long expiresAt
) {
}
