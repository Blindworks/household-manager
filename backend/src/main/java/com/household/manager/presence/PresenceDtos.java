package com.household.manager.presence;

import com.household.manager.model.entity.PresenceDevice;

import java.time.LocalDateTime;
import java.util.List;

/** Request-/Response-Records der Anwesenheits-API. Alle Zeitstempel in Haushaltszeit. */
public final class PresenceDtos {

    private PresenceDtos() {
    }

    public record DeviceRequest(Long userId, String name, String host, Boolean active) {
    }

    public record DeviceAdminResponse(Long id, Long userId, String name, String host, boolean active) {
        public static DeviceAdminResponse from(PresenceDevice device) {
            return new DeviceAdminResponse(device.getId(), device.getUserId(), device.getName(),
                    device.getHost(), device.isActive());
        }
    }

    /**
     * Bewusst OHNE {@code host}: {@code GET /v1/presence/status} ist KIOSK-lesbar
     * (Dashboard-Kachel auf dem Wandtablet), waehrend die Geraete-Stammdaten
     * (inklusive IP) laut Spec ADMIN sind. Wer die IP braucht (Admin-Seite),
     * holt sie ueber {@code GET /v1/presence/devices} und verknuepft ueber
     * {@code id} — nicht "der Vollstaendigkeit halber" hier ergaenzen.
     */
    public record DeviceStatusResponse(Long id, String name, boolean active,
                                        LocalDateTime lastSeenAt, LocalDateTime lastCheckedAt) {
    }

    public record PersonStatus(Long userId, String displayName, String state,
                                LocalDateTime lastSeenAt, List<DeviceStatusResponse> devices) {
    }

    /**
     * {@code householdState} traegt dasselbe Vokabular wie {@link PersonStatus#state}
     * ({@code on}/{@code off}/{@code unavailable}/{@code unknown} —
     * {@link PresenceEvaluator#entityState}). {@code unknown} fasst dabei ZWEI
     * verschiedene Ursachen zusammen: "keine Personen erfasst" (keine Geraete in
     * der DB) und "Personen erfasst, aber keine Aussage moeglich" (Anlauf-Karenz
     * oder eingefrorenes Aggregat, siehe {@link PresenceStatusService}). Das
     * Frontend unterscheidet ueber {@code persons.isEmpty()}, nicht ueber den
     * Wert von {@code householdState} selbst.
     */
    public record StatusResponse(String householdState, List<PersonStatus> persons) {
    }

    public record SettingsDto(Long awayGraceMinutes) {
    }
}
