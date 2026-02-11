package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Current reading response for Airrohr fine dust sensor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AirrohrReadingResponse {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingTime;

    private String softwareVersion;

    private Long ageSeconds;

    private BigDecimal sdsP1;

    private BigDecimal sdsP2;
}
