package com.household.manager.repository;

import com.household.manager.model.entity.MeterReading;
import com.household.manager.model.entity.MeterType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for {@link MeterReading} entity operations.
 * <p>
 * Provides data access methods for meter readings with support for
 * filtering by meter type and date ranges.
 */
@Repository
public interface MeterReadingRepository extends JpaRepository<MeterReading, Long> {

    /**
     * Find all meter readings for a specific meter type, ordered by date descending.
     * <p>
     * Most recent readings appear first in the result list.
     *
     * @param meterType the type of meter to filter by
     * @return list of meter readings for the specified type, sorted by date (newest first)
     */
    List<MeterReading> findByMeterTypeOrderByReadingDateDesc(MeterType meterType);

    /**
     * Find the most recent meter reading for a specific meter type.
     * <p>
     * Useful for retrieving the latest reading value to calculate consumption
     * or display current meter status.
     *
     * @param meterType the type of meter
     * @return optional containing the most recent reading, or empty if no readings exist
     */
    Optional<MeterReading> findTopByMeterTypeOrderByReadingDateDesc(MeterType meterType);

    /**
     * Find meter readings for a specific meter type within a date range.
     * <p>
     * Useful for generating reports or analyzing consumption trends
     * over specific time periods.
     *
     * @param meterType the type of meter to filter by
     * @param start the start of the date range (inclusive)
     * @param end the end of the date range (inclusive)
     * @return list of meter readings within the specified date range
     */
    List<MeterReading> findByMeterTypeAndReadingDateBetween(
            MeterType meterType,
            LocalDateTime start,
            LocalDateTime end
    );

    /**
     * Find the two most recent meter readings for a specific meter type.
     * <p>
     * Used to calculate consumption by comparing the two most recent readings.
     *
     * @param meterType the type of meter
     * @return list containing up to two most recent readings (newest first)
     */
    List<MeterReading> findTop2ByMeterTypeOrderByReadingDateDesc(MeterType meterType);

    /**
     * Check if a reading exists for a given meter type and reading date.
     *
     * @param meterType the type of meter
     * @param readingDate the reading date and time
     * @return true if a reading exists, false otherwise
     */
    boolean existsByMeterTypeAndReadingDate(MeterType meterType, LocalDateTime readingDate);
}
