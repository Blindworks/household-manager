package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** Baut den letzten bekannten Stand der Haustiere fuer die Kartenseite. */
@Service
@RequiredArgsConstructor
public class TractivePetService {

    private final TractivePollingService pollingService;
    private final TractiveZoneResolver zoneResolver;
    private final TractiveHomeResolver homeResolver;

    public List<TractivePetDto> listPets() {
        // Bewusst der Zeitpunkt des letzten erfolgreichen Polls, nicht "jetzt": nur so
        // urteilt die API ueber denselben Snapshot exakt wie die Entitaet. Mit "jetzt"
        // koennte ein eingefrorener Snapshot waehrend eines Ausfalls ueber die
        // Stille-Schwelle laufen, waehrend die Entitaet auf ihrem alten Wert steht.
        Instant polledAt = pollingService.lastPolledAt();
        if (polledAt == null) {
            return List.of();
        }
        return pollingService.latestSnapshots().stream()
                .map(snapshot -> toDto(snapshot, polledAt))
                .toList();
    }

    private TractivePetDto toDto(TractivePetSnapshot snapshot, Instant now) {
        TractivePositionDto position = snapshot.position();
        TractiveHardwareDto hardware = snapshot.hardware();
        boolean hasPosition = position != null && position.hasCoordinates();

        return new TractivePetDto(
                snapshot.trackerId(),
                snapshot.name(),
                hasPosition ? position.latitude() : null,
                hasPosition ? position.longitude() : null,
                hasPosition ? position.accuracy() : null,
                hasPosition ? position.sensorUsed() : null,
                position != null ? position.reportedAt() : null,
                hardware != null ? hardware.batteryLevel() : null,
                hardware != null ? hardware.isCharging() : null,
                hasPosition
                        ? zoneResolver.resolve(position.latitude(), position.longitude(), snapshot.zones())
                        : TractiveZoneResolver.UNKNOWN,
                homeResolver.resolve(snapshot, now).map(HomeVerdict::atHome).orElse(null));
    }
}
