package com.household.manager.grocery.matching;

/** Urteil zu einem Angebot (über den Fingerabdruck identifiziert); {@code reasonKind} ist bei MATCH/NO meist null. */
public record Judgement(String fingerprint, Verdict verdict, ReasonKind reasonKind, String reason) {
}
