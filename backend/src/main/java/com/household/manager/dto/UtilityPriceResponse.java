package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.household.manager.model.entity.MeterType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for utility price responses.
 * <p>
 * Includes all utility price data with formatting for API responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UtilityPriceResponse {

    /**
     * Unique identifier for the utility price record
     */
    private Long id;

    /**
     * Type of meter (ELECTRICITY or WATER only)
     */
    private MeterType meterType;

    /**
     * Price per unit for the utility.
     * <p>
     * Units depend on meter type:
     * - ELECTRICITY: price per kWh
     * - WATER: price per m³
     */
    private BigDecimal price;

    /**
     * Start date when this price becomes valid (inclusive)
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate validFrom;

    /**
     * End date when this price is valid until (exclusive).
     * <p>
     * Null if the price is valid indefinitely.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate validTo;

    /**
     * Timestamp when this record was created
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * Timestamp when this record was last updated
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
