package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ein Tag der Vorhersage. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherForecastDay {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    private BigDecimal temperatureMin; // °C
    private BigDecimal temperatureMax; // °C
    private BigDecimal precipitation;  // mm
    private BigDecimal windGust;       // km/h
    private Integer icon;              // DWD-Icon-Code
}
