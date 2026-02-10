package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Live response for Tasmota electricity data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TasmotaElectricityLiveResponse {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingTime;

    private BigDecimal momentaneWirkleistung;

    private BigDecimal posWirkenergieTariflos;
}
