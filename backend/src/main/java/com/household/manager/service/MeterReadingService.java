package com.household.manager.service;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.ConsumptionResponse;
import com.household.manager.dto.MeterReadingCreateResponse;
import com.household.manager.dto.MeterReadingRequest;
import com.household.manager.dto.MeterReadingResponse;
import com.household.manager.exception.MeterReadingNotFoundException;
import com.household.manager.model.entity.MeterReading;
import com.household.manager.model.entity.MeterType;
import com.household.manager.repository.MeterReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for managing meter readings and consumption calculations.
 * <p>
 * Handles business logic for creating, retrieving, and calculating
 * consumption data for household utility meters.
 * <p>
 * Readings are expected every Friday at 09:00. When a reading is entered
 * after skipping one or more Fridays, estimated intermediate readings are
 * automatically created via linear interpolation so each weekly period
 * carries a proportional share of the total consumption.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MeterReadingService {

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Neueste zuerst. Verglichen wird der Kalendertag, erst danach die ID: bei zwei
     * Ablesungen desselben Tages ist die zuletzt erfasste die aktuelle, egal welche
     * Uhrzeit sie trägt.
     */
    private static final Comparator<MeterReading> NEWEST_FIRST = Comparator
            .comparing((MeterReading reading) -> reading.getReadingDate().toLocalDate())
            .thenComparing(MeterReading::getId)
            .reversed();

    private final MeterReadingRepository meterReadingRepository;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * Create a new meter reading.
     * <p>
     * If the gap since the previous reading spans more than one calendar week,
     * estimated readings are automatically inserted for each missed Friday at 09:00
     * using linear interpolation of the meter value. The user's actual reading is
     * always saved unchanged.
     *
     * @param request the meter reading request containing meter data
     * @return response containing the created reading and any auto-generated estimates
     * @throws IllegalArgumentException if the reading is not dated after the previous reading,
     *                                  lies in the future, or is less than the previous reading
     */
    @Transactional
    public MeterReadingCreateResponse createMeterReading(MeterReadingRequest request) {
        log.info("Creating new meter reading for type: {}", request.getMeterType());

        Optional<MeterReading> previousReadingOpt = newestFirst(request.getMeterType()).stream().findFirst();

        validateAgainstPrevious(previousReadingOpt, request);

        List<MeterReadingResponse> autoCreated = previousReadingOpt
                .map(prev -> createEstimatedReadingsForMissedFridays(prev, request))
                .orElse(Collections.emptyList());

        MeterReading meterReading = MeterReading.builder()
                .meterType(request.getMeterType())
                .readingValue(request.getReadingValue())
                .readingWeek(resolveReadingWeek(request))
                .readingDate(request.getReadingDate())
                .notes(request.getNotes())
                .estimated(false)
                .build();

        MeterReading savedReading = meterReadingRepository.save(meterReading);
        log.info("Successfully created meter reading with ID: {}", savedReading.getId());

        return MeterReadingCreateResponse.builder()
                .reading(convertToResponseWithConsumption(savedReading))
                .autoCreatedReadings(autoCreated)
                .build();
    }

    /**
     * Korrigiert die aktuelle Ablesung eines Zählers. Ältere Ablesungen sind gesperrt,
     * weil auf ihnen schon weitere Ablesungen aufbauen. Die aus der falschen Ablesung
     * abgeleiteten Schätzwerte werden neu berechnet.
     *
     * @throws MeterReadingNotFoundException wenn die Ablesung nicht existiert
     * @throws IllegalArgumentException wenn sie nicht die aktuelle ist oder die Werte ungültig sind
     */
    @Transactional
    public MeterReadingCreateResponse correctLatestReading(Long id, MeterReadingRequest request) {
        MeterReading reading = findReading(id);
        if (reading.getMeterType() != request.getMeterType()) {
            throw new IllegalArgumentException("Der Zählertyp einer Ablesung lässt sich nicht ändern.");
        }
        List<MeterReading> readings = requireLatest(reading);
        List<MeterReading> derivedEstimates = derivedEstimates(readings);
        Optional<MeterReading> previousReadingOpt = readings.stream()
                .skip(1L + derivedEstimates.size())
                .findFirst();

        validateAgainstPrevious(previousReadingOpt, request);

        String before = describe(reading);
        meterReadingRepository.deleteAll(derivedEstimates);
        List<MeterReadingResponse> autoCreated = previousReadingOpt
                .map(prev -> createEstimatedReadingsForMissedFridays(prev, request))
                .orElse(Collections.emptyList());

        reading.setReadingValue(request.getReadingValue());
        reading.setReadingDate(request.getReadingDate());
        reading.setReadingWeek(resolveReadingWeek(request));
        reading.setNotes(request.getNotes());
        reading.setEstimated(false);
        MeterReading saved = meterReadingRepository.save(reading);
        auditService.record("meter-reading.update", before + " -> " + describe(saved));

        return MeterReadingCreateResponse.builder()
                .reading(convertToResponseWithConsumption(saved))
                .autoCreatedReadings(autoCreated)
                .build();
    }

    /**
     * Löscht die aktuelle Ablesung eines Zählers samt der aus ihr abgeleiteten Schätzwerte.
     *
     * @throws MeterReadingNotFoundException wenn die Ablesung nicht existiert
     * @throws IllegalArgumentException wenn sie nicht die aktuelle ist
     */
    @Transactional
    public void deleteLatestReading(Long id) {
        MeterReading reading = findReading(id);
        List<MeterReading> derivedEstimates = derivedEstimates(requireLatest(reading));

        meterReadingRepository.deleteAll(derivedEstimates);
        meterReadingRepository.delete(reading);
        auditService.record("meter-reading.delete", describe(reading));
    }

    /**
     * Get all meter readings across all meter types for the current calendar year.
     *
     * @return list of meter readings from the current year, newest first
     */
    @Transactional(readOnly = true)
    public List<MeterReadingResponse> getAllMeterReadings() {
        log.debug("Retrieving all meter readings for current year");
        LocalDateTime[] range = currentYearRange();
        return convertAll(meterReadingRepository
                .findAllByReadingDateBetweenOrderByReadingDateDesc(range[0], range[1]));
    }

    /**
     * Get all meter readings for a specific meter type in the current calendar year.
     *
     * @param meterType the type of meter to retrieve readings for
     * @return list of meter readings for the specified type, newest first
     */
    @Transactional(readOnly = true)
    public List<MeterReadingResponse> getMeterReadingsByType(MeterType meterType) {
        log.debug("Retrieving meter readings for type: {}", meterType);
        LocalDateTime[] range = currentYearRange();
        return convertAll(meterReadingRepository
                .findByMeterTypeAndReadingDateBetween(meterType, range[0], range[1]));
    }

    /**
     * Get the most recent meter reading for a specific meter type.
     *
     * @param meterType the type of meter
     * @return response containing the latest meter reading
     * @throws MeterReadingNotFoundException if no readings exist for this meter type
     */
    @Transactional(readOnly = true)
    public MeterReadingResponse getLatestReading(MeterType meterType) {
        log.debug("Retrieving latest reading for type: {}", meterType);
        MeterReading reading = newestFirst(meterType).stream().findFirst()
                .orElseThrow(() -> new MeterReadingNotFoundException(
                        "No readings found for meter type: " + meterType));
        return convertToResponseWithConsumption(reading);
    }

    /**
     * Calculate consumption between the two most recent readings for a specific meter type.
     *
     * @param meterType the type of meter
     * @return response containing detailed consumption information
     * @throws MeterReadingNotFoundException if fewer than two readings exist
     */
    @Transactional(readOnly = true)
    public ConsumptionResponse calculateConsumption(MeterType meterType) {
        log.debug("Calculating consumption for type: {}", meterType);

        List<MeterReading> lastTwoReadings = newestFirst(meterType);

        if (lastTwoReadings.size() < 2) {
            throw new MeterReadingNotFoundException(
                    "Insufficient readings to calculate consumption for meter type: " + meterType +
                            ". At least two readings are required.");
        }

        return buildConsumptionResponse(lastTwoReadings.get(0), lastTwoReadings.get(1));
    }

    // -------------------------------------------------------------------------
    // Missed Friday estimation
    // -------------------------------------------------------------------------

    /**
     * Find all Fridays strictly between the two reading dates and create
     * linearly interpolated estimated readings for each one.
     * <p>
     * Estimated readings are saved directly via the repository (bypassing the
     * value-increase validation, which only applies to user-entered readings).
     *
     * @param previousReading the reading that was recorded before the new one
     * @param request         the incoming user request
     * @return list of response DTOs for the auto-created estimated readings
     */
    private List<MeterReadingResponse> createEstimatedReadingsForMissedFridays(
            MeterReading previousReading, MeterReadingRequest request) {

        List<LocalDate> missedFridays = findMissedFridays(
                previousReading.getReadingDate(), request.getReadingDate());

        if (missedFridays.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("Detected {} missed Friday(s) between {} and {} – creating estimated readings",
                missedFridays.size(), previousReading.getReadingDate(), request.getReadingDate());

        long totalDays = ChronoUnit.DAYS.between(
                previousReading.getReadingDate(), request.getReadingDate());

        BigDecimal totalConsumption = request.getReadingValue()
                .subtract(previousReading.getReadingValue());

        List<MeterReadingResponse> result = new ArrayList<>();

        for (LocalDate missedFriday : missedFridays) {
            LocalDateTime missedDateTime = missedFriday.atTime(9, 0);

            long daysFromPrevious = ChronoUnit.DAYS.between(
                    previousReading.getReadingDate(), missedDateTime);

            BigDecimal fraction = BigDecimal.valueOf(daysFromPrevious)
                    .divide(BigDecimal.valueOf(totalDays), 10, RoundingMode.HALF_UP);

            BigDecimal estimatedValue = previousReading.getReadingValue()
                    .add(totalConsumption.multiply(fraction))
                    .setScale(2, RoundingMode.HALF_UP);

            int week = missedFriday.get(WeekFields.of(Locale.GERMANY).weekOfWeekBasedYear());
            String notes = String.format(
                    "Automatisch erstellter Schätzwert für verpassten Freitag (KW %02d)", week);

            MeterReading estimatedReading = MeterReading.builder()
                    .meterType(request.getMeterType())
                    .readingValue(estimatedValue)
                    .readingDate(missedDateTime)
                    .readingWeek(week)
                    .notes(notes)
                    .estimated(true)
                    .build();

            MeterReading saved = meterReadingRepository.save(estimatedReading);
            log.info("Created estimated reading for {} with value {} (KW {})",
                    missedFriday, estimatedValue, week);

            result.add(MeterReadingResponse.builder()
                    .id(saved.getId())
                    .meterType(saved.getMeterType())
                    .readingValue(saved.getReadingValue())
                    .readingWeek(saved.getReadingWeek())
                    .readingDate(saved.getReadingDate())
                    .notes(saved.getNotes())
                    .estimated(true)
                    .build());
        }

        return result;
    }

    /**
     * Find all Fridays strictly between two datetimes (exclusive of both endpoints).
     *
     * @param previousDate start of the search window (exclusive)
     * @param currentDate  end of the search window (exclusive)
     * @return list of missed Fridays in chronological order
     */
    private List<LocalDate> findMissedFridays(LocalDateTime previousDate, LocalDateTime currentDate) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate cursor = previousDate.toLocalDate().plusDays(1);
        LocalDate end = currentDate.toLocalDate();

        while (cursor.isBefore(end)) {
            if (cursor.getDayOfWeek() == DayOfWeek.FRIDAY) {
                result.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Validation & conversion helpers
    // -------------------------------------------------------------------------

    private void validateAgainstPrevious(Optional<MeterReading> previousReadingOpt,
                                         MeterReadingRequest request) {
        LocalDate newDate = request.getReadingDate().toLocalDate();
        if (newDate.isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("Das Ablesedatum darf nicht in der Zukunft liegen.");
        }
        previousReadingOpt.ifPresent(previous -> {
            LocalDate previousDate = previous.getReadingDate().toLocalDate();
            if (!newDate.isAfter(previousDate)) {
                throw new IllegalArgumentException(String.format(
                        "Das Ablesedatum muss nach der letzten Ablesung vom %s liegen.",
                        previousDate.format(GERMAN_DATE)));
            }
            if (request.getReadingValue().compareTo(previous.getReadingValue()) < 0) {
                throw new IllegalArgumentException(String.format(Locale.GERMANY,
                        "Der Zählerstand %.2f ist kleiner als die letzte Ablesung vom %s (%.2f).",
                        request.getReadingValue(), previousDate.format(GERMAN_DATE),
                        previous.getReadingValue()));
            }
        });
    }

    private List<MeterReading> newestFirst(MeterType meterType) {
        return meterReadingRepository.findByMeterTypeOrderByReadingDateDesc(meterType).stream()
                .sorted(NEWEST_FIRST)
                .toList();
    }

    private MeterReading findReading(Long id) {
        return meterReadingRepository.findById(id)
                .orElseThrow(() -> new MeterReadingNotFoundException("Ablesung nicht gefunden: " + id));
    }

    /** Alle Ablesungen des Zählers, neueste zuerst; die übergebene muss die aktuelle sein. */
    private List<MeterReading> requireLatest(MeterReading reading) {
        List<MeterReading> readings = newestFirst(reading.getMeterType());
        if (!readings.get(0).getId().equals(reading.getId())) {
            throw new IllegalArgumentException(
                    "Nur die aktuelle Ablesung eines Zählers lässt sich korrigieren oder löschen.");
        }
        return readings;
    }

    /** Die Schätzwerte zwischen der aktuellen und der vorherigen echten Ablesung. */
    private List<MeterReading> derivedEstimates(List<MeterReading> newestFirst) {
        return newestFirst.stream()
                .skip(1)
                .takeWhile(MeterReading::isEstimated)
                .toList();
    }

    private String describe(MeterReading reading) {
        return String.format("%s %s: %s", reading.getMeterType(),
                reading.getReadingDate().toLocalDate().format(GERMAN_DATE),
                reading.getReadingValue().toPlainString());
    }

    private List<MeterReadingResponse> convertAll(List<MeterReading> readings) {
        Map<MeterType, List<MeterReading>> byType = new EnumMap<>(MeterType.class);
        return readings.stream()
                .sorted(NEWEST_FIRST)
                .map(reading -> convertToResponse(reading,
                        byType.computeIfAbsent(reading.getMeterType(), this::newestFirst)))
                .collect(Collectors.toList());
    }

    private MeterReadingResponse convertToResponseWithConsumption(MeterReading reading) {
        return convertToResponse(reading, newestFirst(reading.getMeterType()));
    }

    private MeterReadingResponse convertToResponse(MeterReading reading, List<MeterReading> newestFirst) {
        boolean latest = !newestFirst.isEmpty() && newestFirst.get(0).getId().equals(reading.getId());
        MeterReadingResponse response = MeterReadingResponse.builder()
                .id(reading.getId())
                .meterType(reading.getMeterType())
                .readingValue(reading.getReadingValue())
                .readingWeek(reading.getReadingWeek())
                .readingDate(reading.getReadingDate())
                .notes(reading.getNotes())
                .estimated(reading.isEstimated())
                .latest(latest)
                .createdAt(reading.getCreatedAt())
                .updatedAt(reading.getUpdatedAt())
                .build();

        if (latest && newestFirst.size() >= 2) {
            MeterReading previousReading = newestFirst.get(1);
            BigDecimal consumption = reading.getReadingValue()
                    .subtract(previousReading.getReadingValue());
            long daysBetween = ChronoUnit.DAYS.between(
                    previousReading.getReadingDate(), reading.getReadingDate());

            response.setConsumption(consumption);
            response.setDaysSinceLastReading((int) daysBetween);
        }

        return response;
    }

    private ConsumptionResponse buildConsumptionResponse(MeterReading currentReading,
                                                         MeterReading previousReading) {
        BigDecimal consumption = currentReading.getReadingValue()
                .subtract(previousReading.getReadingValue());

        long daysBetween = ChronoUnit.DAYS.between(
                previousReading.getReadingDate(), currentReading.getReadingDate());

        BigDecimal averageDailyConsumption = null;
        if (daysBetween > 0) {
            averageDailyConsumption = consumption
                    .divide(BigDecimal.valueOf(daysBetween), 2, RoundingMode.HALF_UP);
        }

        return ConsumptionResponse.builder()
                .meterType(currentReading.getMeterType())
                .currentReading(currentReading.getReadingValue())
                .previousReading(previousReading.getReadingValue())
                .consumption(consumption)
                .currentReadingDate(currentReading.getReadingDate())
                .previousReadingDate(previousReading.getReadingDate())
                .daysBetweenReadings((int) daysBetween)
                .averageDailyConsumption(averageDailyConsumption)
                .build();
    }

    private LocalDateTime[] currentYearRange() {
        int year = Year.now().getValue();
        return new LocalDateTime[]{
                LocalDateTime.of(year, 1, 1, 0, 0, 0),
                LocalDateTime.of(year, 12, 31, 23, 59, 59)
        };
    }

    private Integer resolveReadingWeek(MeterReadingRequest request) {
        if (request.getReadingWeek() != null) {
            return request.getReadingWeek();
        }
        if (request.getReadingDate() == null) {
            return null;
        }
        return request.getReadingDate()
                .get(WeekFields.of(Locale.GERMANY).weekOfWeekBasedYear());
    }
}
