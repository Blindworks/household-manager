package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Aktuelle Wetterbedingungen (erster Vorhersagewert). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeatherConditions {

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime time;

    private BigDecimal temperature;     // °C
    private BigDecimal precipitation;   // mm
    private BigDecimal windSpeed;       // wie geliefert (in Task 9 verifizieren)
    private Integer windDirection;      // Grad
    private Integer humidity;           // %
    private BigDecimal pressure;        // hPa
    private Integer icon;               // DWD-Icon-Code
}
