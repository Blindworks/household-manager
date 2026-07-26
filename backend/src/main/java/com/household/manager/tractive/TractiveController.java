package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Liefert den letzten bekannten Stand der Haustiere fuer die Kartenseite. */
@RestController
@RequestMapping("/v1/tractive")
@RequiredArgsConstructor
public class TractiveController {

    private final TractivePollingService pollingService;
    private final TractiveZoneResolver zoneResolver;

    @GetMapping("/pets")
    public List<TractivePetDto> pets() {
        return pollingService.latestSnapshots().stream().map(this::toDto).toList();
    }

    private TractivePetDto toDto(TractivePetSnapshot snapshot) {
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
                        : TractiveZoneResolver.UNKNOWN);
    }
}
