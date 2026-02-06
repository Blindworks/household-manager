package com.household.manager.service;

import com.household.manager.dto.UtilityPriceRequest;
import com.household.manager.dto.UtilityPriceResponse;
import com.household.manager.exception.UtilityPriceNotFoundException;
import com.household.manager.model.entity.MeterType;
import com.household.manager.model.entity.UtilityPrice;
import com.household.manager.repository.UtilityPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for managing utility prices.
 * <p>
 * Handles business logic for creating, retrieving, and managing
 * utility prices for electricity and water with validity periods.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UtilityPriceService {

    private final UtilityPriceRepository utilityPriceRepository;

    /**
     * Create a new utility price.
     * <p>
     * Validates that:
     * - Only ELECTRICITY or WATER meter types are allowed
     * - validFrom is before validTo (if validTo is provided)
     * - No overlapping validity periods exist for the same meter type
     *
     * @param request the utility price request containing price data
     * @return response containing the created utility price
     * @throws IllegalArgumentException if validation fails
     */
    @Transactional
    public UtilityPriceResponse createUtilityPrice(UtilityPriceRequest request) {
        log.info("Creating new utility price for type: {}", request.getMeterType());

        validateMeterType(request.getMeterType());
        validateValidityPeriod(request.getValidFrom(), request.getValidTo());
        validateNoOverlappingPeriods(request.getMeterType(), request.getValidFrom(), request.getValidTo(), null);

        UtilityPrice utilityPrice = UtilityPrice.builder()
                .meterType(request.getMeterType())
                .price(request.getPrice())
                .validFrom(request.getValidFrom())
                .validTo(request.getValidTo())
                .build();

        UtilityPrice savedPrice = utilityPriceRepository.save(utilityPrice);
        log.info("Successfully created utility price with ID: {}", savedPrice.getId());

        return convertToResponse(savedPrice);
    }

    /**
     * Get all utility prices across all meter types.
     * <p>
     * Results are ordered by valid_from date descending (most recent first).
     *
     * @return list of all utility prices
     */
    @Transactional(readOnly = true)
    public List<UtilityPriceResponse> getAllUtilityPrices() {
        log.debug("Retrieving all utility prices");
        List<UtilityPrice> prices = utilityPriceRepository.findAll();
        return prices.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get all utility prices for a specific meter type.
     * <p>
     * Results are ordered by valid_from date descending (most recent first).
     *
     * @param meterType the type of meter to retrieve prices for
     * @return list of utility prices for the specified type
     */
    @Transactional(readOnly = true)
    public List<UtilityPriceResponse> getUtilityPricesByMeterType(MeterType meterType) {
        log.debug("Retrieving utility prices for type: {}", meterType);
        validateMeterType(meterType);

        List<UtilityPrice> prices = utilityPriceRepository.findByMeterTypeOrderByValidFromDesc(meterType);
        return prices.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get the current price for a specific meter type.
     * <p>
     * Returns the price valid for today's date.
     *
     * @param meterType the type of meter
     * @return response containing the current utility price
     * @throws UtilityPriceNotFoundException if no current price exists
     */
    @Transactional(readOnly = true)
    public UtilityPriceResponse getCurrentPriceForMeterType(MeterType meterType) {
        log.debug("Retrieving current price for type: {}", meterType);
        validateMeterType(meterType);

        LocalDate today = LocalDate.now();
        UtilityPrice price = utilityPriceRepository.findCurrentPriceForMeterType(meterType, today)
                .orElseThrow(() -> new UtilityPriceNotFoundException(
                        "No current price found for meter type: " + meterType));

        return convertToResponse(price);
    }

    /**
     * Delete a utility price by ID.
     *
     * @param id the ID of the utility price to delete
     * @throws UtilityPriceNotFoundException if the price doesn't exist
     */
    @Transactional
    public void deleteUtilityPrice(Long id) {
        log.info("Deleting utility price with ID: {}", id);

        if (!utilityPriceRepository.existsById(id)) {
            throw new UtilityPriceNotFoundException("Utility price not found with ID: " + id);
        }

        utilityPriceRepository.deleteById(id);
        log.info("Successfully deleted utility price with ID: {}", id);
    }

    /**
     * Validate that only ELECTRICITY or WATER meter types are used.
     *
     * @param meterType the meter type to validate
     * @throws IllegalArgumentException if meter type is not ELECTRICITY or WATER
     */
    private void validateMeterType(MeterType meterType) {
        if (meterType != MeterType.ELECTRICITY && meterType != MeterType.WATER) {
            log.warn("Invalid meter type for utility price: {}", meterType);
            throw new IllegalArgumentException(
                    "Utility prices are only supported for ELECTRICITY and WATER meter types. " +
                    "Provided: " + meterType);
        }
    }

    /**
     * Validate that validFrom is before validTo.
     *
     * @param validFrom the start date
     * @param validTo the end date (can be null)
     * @throws IllegalArgumentException if validFrom is not before validTo
     */
    private void validateValidityPeriod(LocalDate validFrom, LocalDate validTo) {
        if (validTo != null && !validFrom.isBefore(validTo)) {
            log.warn("Invalid validity period: validFrom={}, validTo={}", validFrom, validTo);
            throw new IllegalArgumentException(
                    String.format("Valid from date (%s) must be before valid to date (%s)",
                            validFrom, validTo));
        }
    }

    /**
     * Validate that no overlapping validity periods exist for the same meter type.
     *
     * @param meterType the meter type
     * @param validFrom the start date
     * @param validTo the end date (can be null for indefinite)
     * @param excludeId the ID to exclude from the check (for updates), null for new records
     * @throws IllegalArgumentException if overlapping periods are found
     */
    private void validateNoOverlappingPeriods(MeterType meterType, LocalDate validFrom,
                                             LocalDate validTo, Long excludeId) {
        // Handle indefinite validTo (null) by setting it to a far future date for overlap check
        LocalDate effectiveValidTo = validTo != null ? validTo : LocalDate.of(9999, 12, 31);

        List<UtilityPrice> overlappingPrices;
        if (excludeId != null) {
            overlappingPrices = utilityPriceRepository.findOverlappingPricesExcludingId(
                    meterType, validFrom, effectiveValidTo, excludeId);
        } else {
            overlappingPrices = utilityPriceRepository.findOverlappingPrices(
                    meterType, validFrom, effectiveValidTo);
        }

        if (!overlappingPrices.isEmpty()) {
            log.warn("Found {} overlapping price periods for meter type {}",
                    overlappingPrices.size(), meterType);
            throw new IllegalArgumentException(
                    String.format("The validity period (%s to %s) overlaps with existing price periods for %s. " +
                            "Please ensure validity periods do not overlap.",
                            validFrom, validTo != null ? validTo : "indefinite", meterType));
        }
    }

    /**
     * Convert a UtilityPrice entity to a response DTO.
     *
     * @param price the utility price entity
     * @return response DTO
     */
    private UtilityPriceResponse convertToResponse(UtilityPrice price) {
        return UtilityPriceResponse.builder()
                .id(price.getId())
                .meterType(price.getMeterType())
                .price(price.getPrice())
                .validFrom(price.getValidFrom())
                .validTo(price.getValidTo())
                .createdAt(price.getCreatedAt())
                .updatedAt(price.getUpdatedAt())
                .build();
    }
}
