package com.household.manager.repository;

import com.household.manager.model.entity.MeterType;
import com.household.manager.model.entity.UtilityPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for {@link UtilityPrice} entity operations.
 * <p>
 * Provides data access methods for utility prices with support for
 * filtering by meter type and validity periods.
 */
@Repository
public interface UtilityPriceRepository extends JpaRepository<UtilityPrice, Long> {

    /**
     * Find all utility prices for a specific meter type, ordered by valid_from descending.
     * <p>
     * Most recent prices appear first in the result list.
     *
     * @param meterType the type of meter to filter by
     * @return list of utility prices for the specified type, sorted by valid_from (newest first)
     */
    List<UtilityPrice> findByMeterTypeOrderByValidFromDesc(MeterType meterType);

    /**
     * Find the current price for a specific meter type on a given date.
     * <p>
     * Returns the price where:
     * - meterType matches
     * - validFrom <= date
     * - validTo is null OR validTo > date
     *
     * @param meterType the type of meter
     * @param date the date to check
     * @return optional containing the current price, or empty if no valid price exists
     */
    @Query("SELECT up FROM UtilityPrice up WHERE up.meterType = :meterType " +
           "AND up.validFrom <= :date " +
           "AND (up.validTo IS NULL OR up.validTo > :date) " +
           "ORDER BY up.validFrom DESC LIMIT 1")
    Optional<UtilityPrice> findCurrentPriceForMeterType(
            @Param("meterType") MeterType meterType,
            @Param("date") LocalDate date
    );

    /**
     * Find all utility prices that overlap with a given validity period for a specific meter type.
     * <p>
     * Used to detect overlapping price periods when creating or updating prices.
     * Two periods overlap if:
     * - (validFrom <= otherValidTo OR otherValidTo IS NULL)
     * - AND (validTo IS NULL OR validTo >= otherValidFrom)
     *
     * @param meterType the type of meter
     * @param validFrom the start date of the period to check
     * @param validTo the end date of the period to check (can be null for indefinite)
     * @return list of overlapping utility prices
     */
    @Query("SELECT up FROM UtilityPrice up WHERE up.meterType = :meterType " +
           "AND up.validFrom < :validTo " +
           "AND (up.validTo IS NULL OR up.validTo > :validFrom)")
    List<UtilityPrice> findOverlappingPrices(
            @Param("meterType") MeterType meterType,
            @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo
    );

    /**
     * Find all utility prices that overlap with a given validity period for a specific meter type,
     * excluding a specific price ID (used for updates).
     *
     * @param meterType the type of meter
     * @param validFrom the start date of the period to check
     * @param validTo the end date of the period to check (can be null for indefinite)
     * @param excludeId the ID of the price to exclude from the check
     * @return list of overlapping utility prices
     */
    @Query("SELECT up FROM UtilityPrice up WHERE up.meterType = :meterType " +
           "AND up.id != :excludeId " +
           "AND up.validFrom < :validTo " +
           "AND (up.validTo IS NULL OR up.validTo > :validFrom)")
    List<UtilityPrice> findOverlappingPricesExcludingId(
            @Param("meterType") MeterType meterType,
            @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo,
            @Param("excludeId") Long excludeId
    );
}
