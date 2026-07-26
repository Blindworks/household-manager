package com.household.manager.tractive.dto;

import java.time.LocalDateTime;

/** Anmeldezustand fuer das Frontend. */
public record TractiveAuthStatusDto(boolean authenticated, String email, LocalDateTime expiresAt) {
}
