package com.household.manager.audit;

import java.util.List;

/**
 * Rohwerte, unter denen ein lesbarer Suchbegriff im Audit-Log steht (siehe
 * {@link AuditEntryDescriber#searchHints}): Aktionen, deren Bezeichnung passt, Flow-Aktoren,
 * deren Flow so heißt, und Detail-Muster für passende Geräte und Entitäten.
 */
public record AuditSearchHints(List<String> actions, List<String> actors, List<String> detailsExact,
                               List<String> detailsPrefix, List<String> detailsContain) {

    public static final AuditSearchHints NONE = new AuditSearchHints(List.of(), List.of(), List.of(), List.of(), List.of());
}
