package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Status der Wetter-Polling-Aufgabe. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherPollingStatusResponse {

    private String stationId;
    private String schedule;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastPollTime;

    private String lastError;
}
