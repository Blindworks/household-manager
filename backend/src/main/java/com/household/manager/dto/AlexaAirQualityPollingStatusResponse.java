package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Status response for Alexa air quality polling.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlexaAirQualityPollingStatusResponse {

    private String schedule;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastPollTime;

    private String lastError;
}
