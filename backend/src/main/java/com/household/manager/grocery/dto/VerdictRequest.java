package com.household.manager.grocery.dto;

/** Nutzerurteil zu einem Angebot: {@code fits} true = „passt“ (nur bei MAYBE), false = „passt nicht“; fehlt es, ist die Anfrage ungültig (400). */
public record VerdictRequest(String fingerprint, Boolean fits) {
}
