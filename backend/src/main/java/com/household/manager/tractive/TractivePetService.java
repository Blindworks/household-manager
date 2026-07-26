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
        // Ein gemeinsamer Zeitpunkt: sonst koennten zwei Tiere desselben Abrufs
        // unterschiedliche Stille-Schwellen sehen.
        Instant now = Instant.now();
        return pollingService.latestSnapshots().stream()
                .map(snapshot -> toDto(snapshot, now))
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
