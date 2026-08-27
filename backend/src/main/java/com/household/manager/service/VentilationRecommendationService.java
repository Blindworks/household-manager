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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Einzige Definition von "Lüften lohnt sich" (Muster TractiveHomeResolver):
 * REST-Endpunkt und Entity-Reporter fragen dieselbe Klasse, damit Hub-Karte
 * und Flow-Trigger nie auseinanderlaufen.
 *
 * <p>"Draußen" ist nicht gleich "Quelle WEATHER": reale Außenfühler am Haus
 * (konfiguriert über {@code ventilation.outdoor-sensor-names}) hängen an derselben
 * Zigbee-Quelle wie die Raumsensoren. Sie zählen deshalb nie als Raum — sonst
 * verglich die Empfehlung den Garten mit dem DWD-Wert und meldete "Lüften lohnt
 * sich" — und liefern bevorzugt die Außentemperatur.
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

        Set<String> outdoorNames = normalizedOutdoorNames();
        Optional<BigDecimal> outdoor = outdoorTemperature(readings, outdoorNames, staleLimit);
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
                .filter(r -> !isOutdoorSensor(r, outdoorNames))
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

    /**
     * Außentemperatur: der erste frische reale Außenfühler in der konfigurierten
     * Reihenfolge, sonst der DWD-Wert. Der Fühler am Haus misst näher an dem, was
     * beim Öffnen des Fensters tatsächlich hereinkommt, als die DWD-Station.
     */
    private Optional<BigDecimal> outdoorTemperature(
            List<CurrentTemperatureReading> readings, Set<String> outdoorNames, LocalDateTime staleLimit) {
        for (String name : outdoorNames) {
            Optional<BigDecimal> sensor = readings.stream()
                    .filter(r -> !OUTDOOR_SOURCE.equals(r.getSource()))
                    .filter(r -> name.equals(normalize(r.getName())))
                    .filter(r -> isFresh(r, staleLimit))
                    .map(CurrentTemperatureReading::getTemperature)
                    .filter(Objects::nonNull)
                    .findFirst();
            if (sensor.isPresent()) {
                return sensor;
            }
        }
        return readings.stream()
                .filter(r -> OUTDOOR_SOURCE.equals(r.getSource()))
                .filter(r -> isFresh(r, staleLimit))
                .map(CurrentTemperatureReading::getTemperature)
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * Reihenfolge erhalten: sie ist die Priorität der Außenfühler untereinander,
     * ein {@link java.util.HashSet} würde sie verwerfen.
     */
    private Set<String> normalizedOutdoorNames() {
        List<String> configured = properties.getOutdoorSensorNames();
        if (configured == null) {
            return Set.of();
        }
        return configured.stream()
                .map(this::normalize)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isOutdoorSensor(CurrentTemperatureReading reading, Set<String> outdoorNames) {
        return outdoorNames.contains(normalize(reading.getName()));
    }

    private String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isFresh(CurrentTemperatureReading reading, LocalDateTime staleLimit) {
        return reading.getMeasuredAt() != null && !reading.getMeasuredAt().isBefore(staleLimit);
    }
}
