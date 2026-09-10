package com.household.manager.dto;

import lombok.Builder;

/** API-Repräsentation eines Haus-Modus für die Modus-Leiste des Dashboards. */
@Builder
public record ModeResponse(
        String entityId,
        String displayName,
        String icon,
        String state,
        /**
         * True, wenn fuer diesen Modus gerade ein Zeitfenster offen ist. Das Tablet-Dashboard
         * zeigt ihn dann direkt als Knopf neben der eingeklappten Modus-Leiste.
         */
        boolean quickAccess
) {
}
