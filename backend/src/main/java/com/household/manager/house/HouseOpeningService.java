package com.household.manager.house;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.HouseOpening;
import com.household.manager.model.entity.HouseOpeningKind;
import com.household.manager.model.entity.HouseOpeningSize;
import com.household.manager.repository.HouseOpeningRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pflegt Fenster und Tueren im Digitalen Zwilling. Einzige Definition der gueltigen Groessen
 * (Breite in Plan-Einheiten, 40 je Meter), Etagen, Geraete-Id und des Koordinatenbereichs 0-500.
 */
@Service
@RequiredArgsConstructor
public class HouseOpeningService {

    private static final Pattern DEVICE_ID = Pattern.compile("^[a-z0-9_-]{1,100}$");
    private static final Set<String> FLOORS = Set.of("basement", "ground", "upper");
    private static final double MIN_COORDINATE = 0;
    private static final double MAX_COORDINATE = 500;
    private static final double LENGTH_TOLERANCE = 0.05;

    /** Breite je Art und Groesse in Plan-Einheiten; was fehlt, gehoert nicht zur Art. */
    private static final Map<HouseOpeningKind, Map<HouseOpeningSize, Double>> WIDTHS = Map.of(
            HouseOpeningKind.WINDOW, Map.of(
                    HouseOpeningSize.SMALL, 24.0,
                    HouseOpeningSize.NORMAL, 40.0,
                    HouseOpeningSize.WIDE, 64.0),
            HouseOpeningKind.DOOR, Map.of(
                    HouseOpeningSize.NORMAL, 36.0,
                    HouseOpeningSize.TERRACE, 72.0));

    private final HouseOpeningRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<HouseOpeningDtos.Response> list() {
        return repository.findAllByOrderByIdAsc().stream().map(HouseOpeningDtos.Response::from).toList();
    }

    @Transactional
    public HouseOpeningDtos.Response create(HouseOpeningDtos.Request request) {
        HouseOpening opening = new HouseOpening();
        apply(opening, request);
        HouseOpening saved = save(opening);
        auditService.record("house.opening.create", describe(saved));
        return HouseOpeningDtos.Response.from(saved);
    }

    @Transactional
    public HouseOpeningDtos.Response update(long id, HouseOpeningDtos.Request request) {
        HouseOpening opening = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fenster/Tuer", "id", id));
        apply(opening, request);
        HouseOpening saved = save(opening);
        auditService.record("house.opening.update", describe(saved));
        return HouseOpeningDtos.Response.from(saved);
    }

    @Transactional
    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Fenster/Tuer", "id", id);
        }
        repository.deleteById(id);
        auditService.record("house.opening.delete", String.valueOf(id));
    }

    private HouseOpening save(HouseOpening opening) {
        try {
            return repository.save(opening);
        } catch (DataIntegrityViolationException e) {
            // Wettlauf an der Vorabpruefung vorbei: der Unique-Constraint ist die letzte Instanz.
            throw new DuplicateEntityException("Dieser Kontakt ist bereits einem anderen Fenster oder einer Tuer zugeordnet.");
        }
    }

    private void apply(HouseOpening opening, HouseOpeningDtos.Request request) {
        if (request.floor() == null || !FLOORS.contains(request.floor())) {
            throw new IllegalArgumentException("Etage muss basement, ground oder upper sein.");
        }
        if (request.kind() == null || request.size() == null || request.hinge() == null || request.swing() == null) {
            throw new IllegalArgumentException("Art, Groesse, Anschlag und Aufschwung sind Pflicht.");
        }
        Double width = WIDTHS.get(request.kind()).get(request.size());
        if (width == null) {
            throw new IllegalArgumentException("Groesse " + request.size() + " passt nicht zu " + request.kind() + ".");
        }
        if (request.deviceId() != null && !DEVICE_ID.matcher(request.deviceId()).matches()) {
            throw new IllegalArgumentException("Ungueltige Geraete-Id.");
        }
        double x1 = coordinate(request.x1(), "x1");
        double y1 = coordinate(request.y1(), "y1");
        double x2 = coordinate(request.x2(), "x2");
        double y2 = coordinate(request.y2(), "y2");

        boolean horizontal = y1 == y2;
        boolean vertical = x1 == x2;
        if (horizontal == vertical) {
            throw new IllegalArgumentException("Die Strecke muss waagerecht oder senkrecht sein.");
        }
        double length = horizontal ? Math.abs(x2 - x1) : Math.abs(y2 - y1);
        if (Math.abs(length - width) > LENGTH_TOLERANCE) {
            throw new IllegalArgumentException("Laenge " + length + " passt nicht zur Groesse (" + width + ").");
        }

        if (request.deviceId() != null) {
            repository.findByDeviceId(request.deviceId())
                    .filter(other -> !other.getId().equals(opening.getId()))
                    .ifPresent(other -> {
                        throw new DuplicateEntityException(
                                "Dieser Kontakt ist bereits einem anderen Fenster oder einer Tuer zugeordnet.");
                    });
        }

        boolean swap = horizontal ? x1 > x2 : y1 > y2;
        opening.setFloor(request.floor());
        opening.setKind(request.kind());
        opening.setSize(request.size());
        opening.setX1(plan(swap ? x2 : x1));
        opening.setY1(plan(swap ? y2 : y1));
        opening.setX2(plan(swap ? x1 : x2));
        opening.setY2(plan(swap ? y1 : y2));
        opening.setHinge(request.hinge());
        opening.setSwing(request.swing());
        opening.setDeviceId(request.deviceId());
        opening.setUpdatedAt(LocalDateTime.now(clock));
    }

    /** Rundet vor allen Vergleichen auf eine Nachkommastelle, damit Pruefung und Speicherwert uebereinstimmen. */
    private static double coordinate(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value < MIN_COORDINATE || value > MAX_COORDINATE) {
            throw new IllegalArgumentException("Koordinate " + name + " muss zwischen 0 und 500 liegen.");
        }
        return plan(value).doubleValue();
    }

    private static BigDecimal plan(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
    }

    private static String describe(HouseOpening o) {
        return o.getId() + " " + o.getKind() + " " + o.getSize() + " " + o.getFloor();
    }
}
