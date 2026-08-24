package com.household.manager.service;

import com.household.manager.dto.ConsumptionPoint;
import com.household.manager.dto.MeterConsumptionSeries;
import com.household.manager.model.entity.MeterReading;
import com.household.manager.model.entity.MeterType;
import com.household.manager.repository.MeterReadingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Aggregiert die woechentlich erfassten Zaehlerstaende zu Verbrauchsreihen fuer die
 * Tablet-Ansicht.
 *
 * <p>Der Verbrauch ist die Differenz zweier aufeinanderfolgender Ablesungen. Das
 * {@code consumption}-Feld der bestehenden API taugt dafuer nicht: es ist nur bei der
 * jeweils neuesten Ablesung eines Typs gefuellt, und die Liste ist aufs laufende
 * Kalenderjahr beschraenkt.
 *
 * <p>Jeder Zaehlertyp wird fuer sich ausgewertet - faellt einer aus, kommen die
 * anderen trotzdem (Muster {@code TemperatureSeriesService}).
 */
@Service
@Slf4j
public class MeterConsumptionSeriesService {

    private final MeterReadingRepository repository;
    /** Injizierbar, damit Tests ein festes "heute" setzen koennen. */
    private final Supplier<LocalDate> today;

    /**
     * Der Konstruktor fuer Spring. Das {@code @Autowired} ist Pflicht, nicht Zierde:
     * die Klasse hat zwei Konstruktoren, und Spring waehlt nur dann selbsttaetig
     * einen aus, wenn es genau einer ist - sonst sucht es den Default-Konstruktor
     * und der Anwendungsstart bricht ab.
     */
    @Autowired
    public MeterConsumptionSeriesService(MeterReadingRepository repository) {
        this(repository, LocalDate::now);
    }

    MeterConsumptionSeriesService(MeterReadingRepository repository, Supplier<LocalDate> today) {
        this.repository = repository;
        this.today = today;
    }

    @Transactional(readOnly = true)
    public List<MeterConsumptionSeries> getSeries(ConsumptionRange range) {
        LocalDate from = range.windowStart(today.get());
        List<MeterConsumptionSeries> result = new ArrayList<>();
        for (MeterType type : MeterType.values()) {
            seriesFor(type, range, from).ifPresent(result::add);
        }
        return result;
    }

    private Optional<MeterConsumptionSeries> seriesFor(MeterType type, ConsumptionRange range,
                                                       LocalDate from) {
        try {
            List<ConsumptionPoint> points = points(type, range, from);
            return points.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new MeterConsumptionSeries(type, unitOf(type), points));
        } catch (Exception e) {
            log.warn("Verbrauchsreihe fuer {} konnte nicht gebildet werden: {}", type, e.toString());
            return Optional.empty();
        }
    }

    private List<ConsumptionPoint> points(MeterType type, ConsumptionRange range, LocalDate from) {
        List<MeterReading> readings = repository.findByMeterTypeOrderByReadingDateAsc(type);
        List<ConsumptionPoint> weekly = new ArrayList<>();

        for (int i = 1; i < readings.size(); i++) {
            MeterReading current = readings.get(i);
            BigDecimal consumption = current.getReadingValue()
                    .subtract(readings.get(i - 1).getReadingValue());
            LocalDate date = current.getReadingDate().toLocalDate();

            if (consumption.signum() < 0) {
                // Zaehlertausch oder -reset: ein Minusbalken waere eine Falschaussage.
                log.warn("Negative Differenz bei {} am {} verworfen: {}", type, date, consumption);
                continue;
            }
            if (date.isBefore(from)) {
                continue;
            }
            weekly.add(new ConsumptionPoint(date, weekLabel(date), consumption,
                    current.isEstimated()));
        }
        return aggregateByPeriod(weekly, range.getResolution());
    }

    /**
     * Fasst Wochenbalken zu Perioden zusammen - noetig, weil zwei Ablesungen in
     * derselbe ISO-Woche fallen koennen (Korrekturablesung) und weil bei
     * {@link ConsumptionResolution#MONTH} mehrere Wochen einen Monatsbalken bilden.
     * Beide Faelle sind dieselbe Operation: Perioden-Schluessel bilden, Verbrauch
     * summieren, "estimated" verodern. Die Eingabe ist bereits aufsteigend sortiert,
     * die {@link LinkedHashMap} erhaelt diese Reihenfolge.
     */
    private static List<ConsumptionPoint> aggregateByPeriod(List<ConsumptionPoint> weekly,
                                                              ConsumptionResolution resolution) {
        Map<String, List<ConsumptionPoint>> groupedByPeriod = new LinkedHashMap<>();
        for (ConsumptionPoint point : weekly) {
            String key = periodKey(point.periodStart(), resolution);
            groupedByPeriod.computeIfAbsent(key, k -> new ArrayList<>()).add(point);
        }

        List<ConsumptionPoint> result = new ArrayList<>();
        for (List<ConsumptionPoint> group : groupedByPeriod.values()) {
            result.add(mergeGroup(group, resolution));
        }
        return result;
    }

    private static String periodKey(LocalDate date, ConsumptionResolution resolution) {
        return resolution == ConsumptionResolution.WEEK
                ? date.get(WeekFields.ISO.weekBasedYear()) + "-" + date.get(WeekFields.ISO.weekOfWeekBasedYear())
                : date.getYear() + "-" + date.getMonthValue();
    }

    private static ConsumptionPoint mergeGroup(List<ConsumptionPoint> group, ConsumptionResolution resolution) {
        ConsumptionPoint first = group.get(0);
        BigDecimal consumption = group.stream()
                .map(ConsumptionPoint::consumption)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean estimated = group.stream().anyMatch(ConsumptionPoint::estimated);

        if (resolution == ConsumptionResolution.WEEK) {
            return new ConsumptionPoint(first.periodStart(), first.label(), consumption, estimated);
        }
        LocalDate periodStart = first.periodStart().withDayOfMonth(1);
        return new ConsumptionPoint(periodStart, MONTH_LABEL.format(periodStart), consumption, estimated);
    }

    private static String weekLabel(LocalDate date) {
        return "KW " + date.get(WeekFields.ISO.weekOfWeekBasedYear());
    }

    /**
     * "Mai 26", "Juli 26", "Dez. 26" - kurz genug fuer eine Drittelspalte auf dem
     * Wandtablet. Nicht jeder deutsche Monat hat laut CLDR eine Kurzform: Mai bis
     * Juli kuerzen sich gar nicht, die uebrigen tragen einen Punkt.
     */
    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMM yy", Locale.GERMAN);

    private static String unitOf(MeterType type) {
        return type == MeterType.ELECTRICITY ? "kWh" : "m³";
    }
}
