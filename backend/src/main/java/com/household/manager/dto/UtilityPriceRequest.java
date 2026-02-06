package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.model.entity.MeterType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data Transfer Object for creating a new utility price.
 * <p>
 * This DTO validates incoming requests to ensure all required data
 * is present and meets business constraints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UtilityPriceRequest {

    /**
     * Type of meter (ELECTRICITY or GAS only)
     */
    @NotNull(message = "Meter type is required")
    private MeterType meterType;

    /**
     * Price per unit for the utility.
     * <p>
     * Must be a positive number with up to 4 decimal places.
     * Units depend on meter type:
     * - ELECTRICITY: price per kWh
     * - GAS: price per m³
     */
    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Price must be greater than zero")
    private BigDecimal price;

    /**
     * Start date when this price becomes valid (inclusive).
     * <p>
     * Format: ISO 8601 date (yyyy-MM-dd)
     */
    @NotNull(message = "Valid from date is required")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate validFrom;

    /**
     * End date when this price is valid until (exclusive).
     * <p>
     * If null, the price is considered valid indefinitely.
     * Must be after validFrom if provided.
     * Format: ISO 8601 date (yyyy-MM-dd)
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate validTo;
}
