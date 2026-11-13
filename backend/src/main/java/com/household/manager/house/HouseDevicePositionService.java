package com.household.manager.house;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.HouseDevicePosition;
import com.household.manager.repository.HouseDevicePositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pflegt die Grundriss-Positionen der Geraete im Digitalen Zwilling. Einzige Definition der
 * gueltigen Geraete-Id und des Koordinatenbereichs (Plan-Einheiten 0-500, eine Nachkommastelle).
 */
@Service
@RequiredArgsConstructor
public class HouseDevicePositionService {

    private static final Pattern DEVICE_ID = Pattern.compile("^[a-z0-9_-]{1,100}$");
    private static final double MIN_COORDINATE = 0;
    private static final double MAX_COORDINATE = 500;
    /** Erlaubte Drehungen eines Geraetekoerpers; null = automatisch. Welches Geraet einen Koerper hat, weiss nur das Frontend. */
    private static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);

    private final HouseDevicePositionRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<HouseDevicePositionDtos.Response> list() {
        return repository.findAllByOrderByDeviceIdAsc().stream()
                .map(HouseDevicePositionDtos.Response::from)
                .toList();
    }

    @Transactional
    public HouseDevicePositionDtos.Response set(String deviceId, Double x, Double y, Integer rotation) {
        requireValidId(deviceId);
        if (rotation != null && !ROTATIONS.contains(rotation)) {
            throw new IllegalArgumentException("Drehung muss 0, 90, 180 oder 270 Grad sein.");
        }
        BigDecimal planX = toPlanValue(x, "x");
        BigDecimal planY = toPlanValue(y, "y");

        HouseDevicePosition position = repository.findById(deviceId)
                .orElseGet(() -> HouseDevicePosition.builder().deviceId(deviceId).build());
        position.setPlanX(planX);
        position.setPlanY(planY);
        position.setRotationDeg(rotation);
        position.setUpdatedAt(LocalDateTime.now(clock));
        HouseDevicePosition saved = repository.save(position);

        auditService.record("house.device-position.update",
                deviceId + " x=" + planX.toPlainString() + " y=" + planY.toPlainString()
                        + " rotation=" + (rotation == null ? "auto" : rotation));
        return HouseDevicePositionDtos.Response.from(saved);
    }

    @Transactional
    public void delete(String deviceId) {
        if (!repository.existsById(deviceId)) {
            return;
        }
        repository.deleteById(deviceId);
        auditService.record("house.device-position.delete", deviceId);
    }

    private static void requireValidId(String deviceId) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            throw new IllegalArgumentException("Ungueltige Geraete-Id.");
        }
    }

    private static BigDecimal toPlanValue(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value < MIN_COORDINATE || value > MAX_COORDINATE) {
            throw new IllegalArgumentException("Koordinate " + name + " muss zwischen 0 und 500 liegen.");
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }
}
