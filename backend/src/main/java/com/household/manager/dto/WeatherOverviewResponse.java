package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** Gesamtantwort der Wetterseite: aktuell + Vorhersage + Warnungen + nextRain. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherOverviewResponse {

    private String stationId;
    private WeatherConditions current;
    private List<WeatherForecastHour> hourlyForecast;
    private List<WeatherWarning> warnings;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime nextRain;
}
