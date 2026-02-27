package com.household.manager.service;

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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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

    private final MeterReadingRepository meterReadingRepository;

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
     * @throws IllegalArgumentException if the new reading is less than the previous reading
     */
    @Transactional
    public MeterReadingCreateResponse createMeterReading(MeterReadingRequest request) {
        log.info("Creating new meter reading for type: {}", request.getMeterType());

        Optional<MeterReading> previousReadingOpt = meterReadingRepository
                .findTopByMeterTypeOrderByReadingDateDesc(request.getMeterType());

        validateReadingValue(previousReadingOpt, request.getReadingValue());

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
     * Get all meter readings across all meter types.
     *
     * @return list of all meter readings with consumption data
     */
    @Transactional(readOnly = true)
    public List<MeterReadingResponse> getAllMeterReadings() {
        log.debug("Retrieving all meter readings");
        return meterReadingRepository.findAll().stream()
                .map(this::convertToResponseWithConsumption)
                .collect(Collectors.toList());
    }

    /**
     * Get all meter readings for a specific meter type.
     *
     * @param meterType the type of meter to retrieve readings for
     * @return list of meter readings for the specified type
     */
    @Transactional(readOnly = true)
    public List<MeterReadingResponse> getMeterReadingsByType(MeterType meterType) {
        log.debug("Retrieving meter readings for type: {}", meterType);
        return meterReadingRepository.findByMeterTypeOrderByReadingDateDesc(meterType).stream()
                .map(this::convertToResponseWithConsumption)
                .collect(Collectors.toList());
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
        MeterReading reading = meterReadingRepository.findTopByMeterTypeOrderByReadingDateDesc(meterType)
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

        List<MeterReading> lastTwoReadings = meterReadingRepository
                .findTop2ByMeterTypeOrderByReadingDateDesc(meterType);

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

    private void validateReadingValue(Optional<MeterReading> previousReadingOpt,
                                      BigDecimal newReadingValue) {
        previousReadingOpt.ifPresent(previousReading -> {
            if (newReadingValue.compareTo(previousReading.getReadingValue()) < 0) {
                log.warn("New reading value {} is less than previous reading {} for meter type {}",
                        newReadingValue, previousReading.getReadingValue(), previousReading.getMeterType());
                throw new IllegalArgumentException(
                        String.format("New reading value (%s) cannot be less than previous reading (%s). " +
                                        "If the meter was reset, please add a note explaining this.",
                                newReadingValue, previousReading.getReadingValue()));
            }
        });
    }

    private MeterReadingResponse convertToResponseWithConsumption(MeterReading reading) {
        MeterReadingResponse response = MeterReadingResponse.builder()
                .id(reading.getId())
                .meterType(reading.getMeterType())
                .readingValue(reading.getReadingValue())
                .readingWeek(reading.getReadingWeek())
                .readingDate(reading.getReadingDate())
                .notes(reading.getNotes())
                .estimated(reading.isEstimated())
                .createdAt(reading.getCreatedAt())
                .updatedAt(reading.getUpdatedAt())
                .build();

        List<MeterReading> lastTwoReadings = meterReadingRepository
                .findTop2ByMeterTypeOrderByReadingDateDesc(reading.getMeterType());

        if (lastTwoReadings.size() == 2 && lastTwoReadings.get(0).getId().equals(reading.getId())) {
            MeterReading previousReading = lastTwoReadings.get(1);
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
