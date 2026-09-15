package com.household.manager.mode;

import com.household.manager.model.entity.ModeQuickAccess;

import java.time.LocalTime;

/**
 * Request-/Response-Records der Pflege-API der Modus-Zeitfenster.
 *
 * <p>Die Zeiten laufen als ISO-Uhrzeit ueber die Leitung ("20:00:00"). Ein HTML-Feld
 * {@code <input type="time">} sendet "20:00" — Jackson parst beides zu {@link LocalTime}.
 */
public final class ModeQuickAccessDtos {

    private ModeQuickAccessDtos() {
    }

    public record Request(String entityId, LocalTime fromTime, LocalTime toTime, Boolean active) {
    }

    /**
     * @param displayName Anzeigename des Helfers, {@code null} wenn es zu der Entity-ID keinen
     *                    Helfer (mehr) gibt. Die Admin-Seite zeigt dann die rohe ID.
     */
    public record Response(Long id, String entityId, String displayName,
                           LocalTime fromTime, LocalTime toTime, boolean active) {

        public static Response from(ModeQuickAccess window, String displayName) {
            return new Response(window.getId(), window.getEntityId(), displayName,
                    window.getFromTime(), window.getToTime(), window.isActive());
        }
    }
}
