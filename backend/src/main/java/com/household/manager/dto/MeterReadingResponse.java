package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.household.manager.model.entity.MeterType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for meter reading responses.
 * <p>
 * Includes all meter reading data plus calculated fields for
 * consumption and time since last reading.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MeterReadingResponse {

    /**
     * Unique identifier for the meter reading
     */
    private Long id;

    /**
     * Type of meter (ELECTRICITY, GAS, or WATER)
     */
    private MeterType meterType;

    /**
     * The meter reading value.
     * <p>
     * Units depend on meter type:
     * - ELECTRICITY: kWh
     * - GAS: m³
     * - WATER: m³
     */
    private BigDecimal readingValue;

    /**
     * Calendar week (KW) of the reading date.
     */
    private Integer readingWeek;

    /**
     * Date and time when the meter reading was taken
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingDate;

    /**
     * Optional notes or comments about this reading
     */
    private String notes;

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

    /**
     * Calculated consumption since the previous reading.
     * <p>
     * Null if this is the first reading for this meter type.
     * Represents the difference between this reading and the previous reading.
     */
    private BigDecimal consumption;

    /**
     * Number of days between this reading and the previous reading.
     * <p>
     * Null if this is the first reading for this meter type.
     * Useful for calculating average daily consumption.
     */
    private Integer daysSinceLastReading;
}
