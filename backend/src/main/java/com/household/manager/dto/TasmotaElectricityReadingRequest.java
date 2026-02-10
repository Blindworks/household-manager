package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for creating a new Tasmota electricity reading.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TasmotaElectricityReadingRequest {

    /**
     * Timestamp reported by the device.
     */
    @NotNull(message = "Reading time is required")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingTime;

    /**
     * Positive active energy (tariflos).
     */
    @NotNull(message = "Positive active energy is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Positive active energy must be zero or greater")
    private BigDecimal posWirkenergieTariflos;

    /**
     * Instantaneous active power.
     */
    @NotNull(message = "Instantaneous active power is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "Instantaneous active power must be zero or greater")
    private BigDecimal momentaneWirkleistung;
}
