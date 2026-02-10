package com.household.manager.repository;

import com.household.manager.model.entity.TasmotaElectricityReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository interface for {@link TasmotaElectricityReading} entity operations.
 */
@Repository
public interface TasmotaElectricityReadingRepository extends JpaRepository<TasmotaElectricityReading, Long> {

    /**
     * Find the most recent Tasmota reading by reading time.
     *
     * @return optional containing the most recent reading, or empty if no readings exist
     */
    Optional<TasmotaElectricityReading> findTopByOrderByReadingTimeDesc();

    /**
     * Check if a reading exists for a given reading time.
     *
     * @param readingTime the reading time
     * @return true if a reading exists, false otherwise
     */
    boolean existsByReadingTime(java.time.LocalDateTime readingTime);
}
