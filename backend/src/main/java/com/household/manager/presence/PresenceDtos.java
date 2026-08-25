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

    public record DeviceStatusResponse(Long id, String name, String host, boolean active,
                                        LocalDateTime lastSeenAt, LocalDateTime lastCheckedAt) {
    }

    public record PersonStatus(Long userId, String displayName, String state,
                                LocalDateTime lastSeenAt, List<DeviceStatusResponse> devices) {
    }

    public record StatusResponse(String householdState, List<PersonStatus> persons) {
    }

    public record SettingsDto(Long awayGraceMinutes) {
    }
}
