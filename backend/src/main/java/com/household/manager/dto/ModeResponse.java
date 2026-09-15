package com.household.manager.dto;

import lombok.Builder;

/**
 * API-Repräsentation eines Haus-Modus für die Modus-Leiste des Dashboards — und, über
 * {@code GET /v1/mode-quick-access/due}, eines beliebigen Helfers im Schnellzugriff.
 *
 * <p>Das fruehere Feld {@code quickAccess} ist entfallen: Faellige Schnellzugriffe liefert
 * der eigene Endpunkt, damit Modi und gewoehnliche Helfer dieselbe Quelle haben.
 */
@Builder
public record ModeResponse(
        String entityId,
        String displayName,
        String icon,
        String state
) {
}
