package com.household.manager.grocery.dto;

/** Nutzerurteil zu einem Angebot: {@code fits} true = „passt“ (nur bei MAYBE), false = „passt nicht“. */
public record VerdictRequest(String fingerprint, boolean fits) {
}
