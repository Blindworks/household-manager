package com.household.manager.presence;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.PresenceDevice;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.PresenceDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Baut die Status-Antwort fuer Admin-Seite und Dashboard-Kachel. Rechnet bei
 * jedem Abruf frisch ueber den {@link PresenceEvaluator} — dieselbe Definition
 * von "anwesend" wie der Poller, damit Kachel und Entitaet nie widersprechen.
 */
@Service
@RequiredArgsConstructor
public class PresenceStatusService {

    private final PresenceDeviceRepository deviceRepository;
    private final AppUserRepository userRepository;
    private final PresenceMonitor monitor;
    private final PresenceEvaluator evaluator;
    private final Clock clock;

    public PresenceDtos.StatusResponse getStatus() {
        Map<Long, List<PresenceDevice>> byUser = deviceRepository.findAll().stream()
                .collect(Collectors.groupingBy(PresenceDevice::getUserId, TreeMap::new,
                        Collectors.toList()));
        Instant now = clock.instant();

        List<PresenceDtos.PersonStatus> persons = new ArrayList<>();
        List<PresenceEvaluator.PersonState> states = new ArrayList<>();
        byUser.forEach((userId, devices) -> {
            PresenceEvaluator.PersonPresence presence = evaluator.evaluate(devices, now);
            states.add(presence.state());
            persons.add(new PresenceDtos.PersonStatus(
                    userId,
                    displayNameOf(userId),
                    PresenceEvaluator.entityState(presence.state()),
                    toLocal(presence.lastSeenAt()),
                    devices.stream().map(this::deviceStatus).toList()));
        });

        String householdState = evaluator.aggregateState(states).orElse("unknown");
        return new PresenceDtos.StatusResponse(householdState, persons);
    }

    private PresenceDtos.DeviceStatusResponse deviceStatus(PresenceDevice device) {
        PresenceMonitor.DeviceProbeStatus status = monitor.statusOf(device.getId()).orElse(null);
        return new PresenceDtos.DeviceStatusResponse(
                device.getId(), device.getName(), device.getHost(), device.isActive(),
                status == null ? null : toLocal(status.lastSeenAt()),
                status == null ? null : toLocal(status.lastCheckedAt()));
    }

    private String displayNameOf(Long userId) {
        return userRepository.findById(userId)
                .map(AppUser::getDisplayName)
                .orElse("Person " + userId);
    }

    private LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }
}
