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
import java.time.LocalDateTime;

/**
 * Data Transfer Object for creating a new meter reading.
 * <p>
 * This DTO validates incoming requests to ensure all required data
 * is present and meets business constraints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeterReadingRequest {

    /**
     * Type of meter (ELECTRICITY, GAS, or WATER)
     */
    @NotNull(message = "Meter type is required")
    private MeterType meterType;

    /**
     * The meter reading value.
     * <p>
     * Must be a positive number with up to 2 decimal places.
     * Units depend on meter type:
     * - ELECTRICITY: kWh
     * - GAS: m³
     * - WATER: m³
     */
    @NotNull(message = "Reading value is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Reading value must be greater than zero")
    private BigDecimal readingValue;

    /**
     * Calendar week (KW) of the reading date.
     * Optional; if not provided it can be derived from readingDate.
     */
    private Integer readingWeek;

    /**
     * Date and time when the meter reading was taken.
     * <p>
     * Format: ISO 8601 (yyyy-MM-dd'T'HH:mm:ss)
     */
    @NotNull(message = "Reading date is required")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingDate;

    /**
     * Optional notes or comments about this reading.
     * <p>
     * Can include information like meter location, reading conditions,
     * or any anomalies observed.
     */
    private String notes;
}
