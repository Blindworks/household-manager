package com.household.manager.blaulicht.dto;

import com.household.manager.blaulicht.BlaulichtCategory;
import com.household.manager.blaulicht.BlaulichtSourceKey;

import java.time.LocalDateTime;

/** Ein Fall für Liste und Dialog. {@code newSinceEvening}: veröffentlicht seit Beginn der letzten Nacht. */
public record BlaulichtEventDto(
        Long id,
        BlaulichtSourceKey source,
        BlaulichtCategory category,
        String locality,
        boolean nearby,
        String title,
        String summary,
        LocalDateTime occurredAt,
        LocalDateTime occurredUntil,
        LocalDateTime publishedAt,
        String url,
        int relevance,
        boolean newSinceEvening
) {
}
