package com.household.manager.security.dto;

import com.household.manager.model.entity.AppUser;

/**
 * Schlanker Nutzer-Eintrag fuer die Personenauswahl im Kalender. Bewusst nur Id,
 * Anzeigename und Aktiv-Flag: keine Rolle, kein Benutzername — auch das
 * KIOSK-Wandtablet liest {@code /v1/users}.
 */
public record HouseholdUserResponse(Long id, String displayName, boolean enabled) {

    public static HouseholdUserResponse of(AppUser user) {
        return new HouseholdUserResponse(user.getId(), user.getDisplayName(), user.isEnabled());
    }
}
