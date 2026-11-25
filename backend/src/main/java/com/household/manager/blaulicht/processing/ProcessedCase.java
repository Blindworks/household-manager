package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.BlaulichtCategory;
import com.household.manager.blaulicht.BlaulichtSourceKey;

import java.time.LocalDateTime;

/** Ein aufbereiteter Fall, bereit zum Speichern. */
public record ProcessedCase(
        BlaulichtSourceKey source,
        String externalId,
        String contentHash,
        BlaulichtCategory category,
        String locality,
        boolean nearby,
        int relevance,
        String title,
        String summary,
        LocalDateTime occurredAt,
        LocalDateTime occurredUntil,
        LocalDateTime publishedAt,
        String url
) {
}
