package com.household.manager.thermostat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/** Verlauf eines Thermostats; {@code null} in einem Punkt heisst "in diesem Zeitfenster keine Messung". */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record HistoryResponse(List<Point> points) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Point(LocalDateTime time, Double currentTemp, Double targetTemp, boolean heating) {
    }
}
