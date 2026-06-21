package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Persistierter Wetter-Snapshot für den Verlauf-Chart. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherReadingHistoryResponse {

    private Long id;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingTime;

    private BigDecimal temperature;
    private BigDecimal precipitation;
    private BigDecimal windSpeed;
    private Integer windDirection;
    private Integer humidity;
    private BigDecimal pressure;
    private Integer icon;
}
