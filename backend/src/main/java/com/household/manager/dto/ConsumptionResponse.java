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
 * Data Transfer Object for consumption calculation responses.
 * <p>
 * Provides detailed consumption information between two meter readings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsumptionResponse {

    /**
     * Type of meter
     */
    private MeterType meterType;

    /**
     * Current (most recent) meter reading value
     */
    private BigDecimal currentReading;

    /**
     * Previous meter reading value
     */
    private BigDecimal previousReading;

    /**
     * Calculated consumption (difference between current and previous reading)
     */
    private BigDecimal consumption;

    /**
     * Date of the current reading
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime currentReadingDate;

    /**
     * Date of the previous reading
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime previousReadingDate;

    /**
     * Number of days between the two readings
     */
    private Integer daysBetweenReadings;

    /**
     * Average daily consumption
     */
    private BigDecimal averageDailyConsumption;
}
