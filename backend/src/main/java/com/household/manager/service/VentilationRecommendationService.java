package com.household.manager.service;

import com.household.manager.config.VentilationProperties;
import com.household.manager.dto.CurrentTemperatureReading;
import com.household.manager.dto.VentilationAssessment;
import com.household.manager.dto.VentilationRoom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Einzige Definition von "Lüften lohnt sich" (Muster TractiveHomeResolver):
 * REST-Endpunkt und Entity-Reporter fragen dieselbe Klasse, damit Hub-Karte
 * und Flow-Trigger nie auseinanderlaufen.
 *
 * <p>Hysterese: eine bestehende Empfehlung erlischt erst, wenn kein Raum mehr
 * über der Raumschwelle liegt oder die Differenz unter die Ausschaltschwelle
 * fällt — sonst schaltete die Entität an der Schwelle im Minutentakt und ein
 * darauf gebauter Telegram-Flow spammte bei jeder on-Flanke.
 */
@Service
@RequiredArgsConstructor
public class VentilationRecommendationService {

    private static final String OUTDOOR_SOURCE = "WEATHER";

    private final TemperatureSeriesService temperatureSeriesService;
    private final VentilationProperties properties;

    /** Letztes Urteil; Basis der Hysterese. Nur unter dem synchronized von assess() angefasst. */
    private boolean lastRecommended = false;

    public synchronized VentilationAssessment assess() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleLimit = now.minusMinutes(properties.getStaleAfterMinutes());
        List<CurrentTemperatureReading> readings = temperatureSeriesService.getCurrent();

        Optional<BigDecimal> outdoor = readings.stream()
                .filter(r -> OUTDOOR_SOURCE.equals(r.getSource()))
                .filter(r -> isFresh(r, staleLimit))
                .map(CurrentTemperatureReading::getTemperature)
                .filter(Objects::nonNull)
                .findFirst();
        if (outdoor.isEmpty()) {
            // Ohne frischen Außenwert gibt es keine Aussage — und keine Hysterese:
            // nach der Rückkehr soll wieder die volle Einschaltschwelle gelten.
            lastRecommended = false;
            return new VentilationAssessment(null, null, List.of(), now);
        }

        BigDecimal outdoorTemp = outdoor.get();
        BigDecimal requiredDifference = lastRecommended
                ? properties.getOffDifferenceCelsius()
                : properties.getMinDifferenceCelsius();

        List<VentilationRoom> rooms = readings.stream()
                .filter(r -> !OUTDOOR_SOURCE.equals(r.getSource()))
                .filter(r -> isFresh(r, staleLimit))
                .filter(r -> r.getTemperature() != null)
                .filter(r -> r.getTemperature().compareTo(properties.getRoomThresholdCelsius()) >= 0)
                .filter(r -> r.getTemperature().subtract(outdoorTemp).compareTo(requiredDifference) >= 0)
                .sorted(Comparator.comparing(CurrentTemperatureReading::getTemperature).reversed())
                .map(r -> new VentilationRoom(r.getName(), r.getTemperature()))
                .toList();

        lastRecommended = !rooms.isEmpty();
        return new VentilationAssessment(lastRecommended, outdoorTemp, rooms, now);
    }

    private boolean isFresh(CurrentTemperatureReading reading, LocalDateTime staleLimit) {
        return reading.getMeasuredAt() != null && !reading.getMeasuredAt().isBefore(staleLimit);
    }
}
