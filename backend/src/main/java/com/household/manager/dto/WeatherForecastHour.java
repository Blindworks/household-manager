package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Ein Stundenpunkt der Vorhersage. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherForecastHour {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime time;

    private BigDecimal temperature;   // °C
    private BigDecimal precipitation; // mm
    private Integer icon;             // DWD-Icon-Code
}
