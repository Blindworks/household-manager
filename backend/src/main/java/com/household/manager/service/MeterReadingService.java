package com.household.manager.service;

import com.household.manager.dto.ConsumptionResponse;
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
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing meter readings and consumption calculations.
 * <p>
 * Handles business logic for creating, retrieving, and calculating
 * consumption data for household utility meters.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MeterReadingService {

    private final MeterReadingRepository meterReadingRepository;

    /**
     * Create a new meter reading.
     * <p>
     * Validates that the new reading is not less than the previous reading
     * (meters should only increase or reset).
     *
     * @param request the meter reading request containing meter data
     * @return response containing the created meter reading with calculated consumption
     * @throws IllegalArgumentException if the new reading is less than the previous reading
     */
    @Transactional
    public MeterReadingResponse createMeterReading(MeterReadingRequest request) {
        log.info("Creating new meter reading for type: {}", request.getMeterType());

        // Validate reading value against previous reading
        validateReadingValue(request.getMeterType(), request.getReadingValue());

        MeterReading meterReading = MeterReading.builder()
                .meterType(request.getMeterType())
                .readingValue(request.getReadingValue())
                .readingWeek(resolveReadingWeek(request))
                .readingDate(request.getReadingDate())
                .notes(request.getNotes())
                .build();

        MeterReading savedReading = meterReadingRepository.save(meterReading);
        log.info("Successfully created meter reading with ID: {}", savedReading.getId());

        return convertToResponseWithConsumption(savedReading);
    }

    /**
     * Get all meter readings across all meter types.
     * <p>
     * Results are ordered by reading date descending (most recent first).
     *
     * @return list of all meter readings with consumption data
     */
    @Transactional(readOnly = true)
    public List<MeterReadingResponse> getAllMeterReadings() {
        log.debug("Retrieving all meter readings");
        List<MeterReading> readings = meterReadingRepository.findAll();
        return readings.stream()
                .map(this::convertToResponseWithConsumption)
                .collect(Collectors.toList());
    }

    /**
     * Get all meter readings for a specific meter type.
     * <p>
     * Results are ordered by reading date descending (most recent first).
     *
     * @param meterType the type of meter to retrieve readings for
     * @return list of meter readings for the specified type
     */
    @Transactional(readOnly = true)
    public List<MeterReadingResponse> getMeterReadingsByType(MeterType meterType) {
        log.debug("Retrieving meter readings for type: {}", meterType);
        List<MeterReading> readings = meterReadingRepository.findByMeterTypeOrderByReadingDateDesc(meterType);
        return readings.stream()
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

        MeterReading currentReading = lastTwoReadings.get(0);
        MeterReading previousReading = lastTwoReadings.get(1);

        return buildConsumptionResponse(currentReading, previousReading);
    }

    /**
     * Validate that a new reading value is valid compared to the previous reading.
     * <p>
     * Meters typically only increase. A reading lower than the previous reading
     * might indicate a meter reset or data entry error.
     *
     * @param meterType the type of meter
     * @param newReadingValue the new reading value to validate
     * @throws IllegalArgumentException if validation fails
     */
    private void validateReadingValue(MeterType meterType, BigDecimal newReadingValue) {
        meterReadingRepository.findTopByMeterTypeOrderByReadingDateDesc(meterType)
                .ifPresent(previousReading -> {
                    if (newReadingValue.compareTo(previousReading.getReadingValue()) < 0) {
                        log.warn("New reading value {} is less than previous reading {} for meter type {}",
                                newReadingValue, previousReading.getReadingValue(), meterType);
                        throw new IllegalArgumentException(
                                String.format("New reading value (%s) cannot be less than previous reading (%s). " +
                                                "If the meter was reset, please add a note explaining this.",
                                        newReadingValue, previousReading.getReadingValue()));
                    }
                });
    }

    /**
     * Convert a MeterReading entity to a response DTO with calculated consumption data.
     *
     * @param reading the meter reading entity
     * @return response DTO with consumption information
     */
    private MeterReadingResponse convertToResponseWithConsumption(MeterReading reading) {
        MeterReadingResponse response = MeterReadingResponse.builder()
                .id(reading.getId())
                .meterType(reading.getMeterType())
                .readingValue(reading.getReadingValue())
                .readingWeek(reading.getReadingWeek())
                .readingDate(reading.getReadingDate())
                .notes(reading.getNotes())
                .createdAt(reading.getCreatedAt())
                .updatedAt(reading.getUpdatedAt())
                .build();

        // Calculate consumption by finding the previous reading
        List<MeterReading> lastTwoReadings = meterReadingRepository
                .findTop2ByMeterTypeOrderByReadingDateDesc(reading.getMeterType());

        if (lastTwoReadings.size() == 2 && lastTwoReadings.get(0).getId().equals(reading.getId())) {
            MeterReading previousReading = lastTwoReadings.get(1);
            BigDecimal consumption = reading.getReadingValue()
                    .subtract(previousReading.getReadingValue());
            long daysBetween = ChronoUnit.DAYS.between(
                    previousReading.getReadingDate(),
                    reading.getReadingDate()
            );

            response.setConsumption(consumption);
            response.setDaysSinceLastReading((int) daysBetween);
        }

        return response;
    }

    /**
     * Build a detailed consumption response from two meter readings.
     *
     * @param currentReading the most recent reading
     * @param previousReading the previous reading
     * @return consumption response with detailed calculations
     */
    private ConsumptionResponse buildConsumptionResponse(
            MeterReading currentReading,
            MeterReading previousReading) {

        BigDecimal consumption = currentReading.getReadingValue()
                .subtract(previousReading.getReadingValue());

        long daysBetween = ChronoUnit.DAYS.between(
                previousReading.getReadingDate(),
                currentReading.getReadingDate()
        );

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
                .get(java.time.temporal.WeekFields.of(java.util.Locale.GERMANY).weekOfWeekBasedYear());
    }
}
