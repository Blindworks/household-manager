package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for Tasmota electricity reading responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TasmotaElectricityReadingResponse {

    /**
     * Unique identifier for the Tasmota electricity reading.
     */
    private Long id;

    /**
     * Timestamp reported by the device.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingTime;

    /**
     * Positive active energy (tariflos).
     */
    private BigDecimal posWirkenergieTariflos;

    /**
     * Instantaneous active power.
     */
    private BigDecimal momentaneWirkleistung;

    /**
     * Timestamp when this record was created.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * Timestamp when this record was last updated.
     */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
