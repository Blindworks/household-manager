package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Messwert eines Amazon Smart Air Quality Monitors fuer das Frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaAirQualityReadingResponse {

    private Long id;

    private String applianceId;

    private String deviceName;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingTime;

    private Integer iaq;

    private BigDecimal pm25;

    private BigDecimal voc;

    private BigDecimal co;

    private BigDecimal temperature;

    private BigDecimal humidity;
}
