package com.household.manager.blaulicht.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Briefing der zuletzt begonnenen Nacht.
 *
 * @param mainCount      Fälle in Bad Vilbel und Ortsteilen
 * @param olderNewCount  seit Nachtbeginn veröffentlichte Fälle, die zu früheren Tagen gehören
 * @param addendumText   leer ohne Nachtrag
 * @param cardVisible    der Hub zeigt die Karte (zwischen Nachtende und {@code cardUntil})
 * @param addendumVisible der Hub zeigt den Nachtrag (ab {@code addendumAt})
 */
public record BlaulichtBriefingDto(
        LocalDateTime nightStart,
        LocalDateTime nightEnd,
        String text,
        int mainCount,
        int nearbyCount,
        int olderNewCount,
        String addendumText,
        int addendumCount,
        boolean cardVisible,
        boolean addendumVisible,
        List<BlaulichtEventDto> events
) {
}
