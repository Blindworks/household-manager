package com.household.manager.tractive.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Positionsbericht aus {@code GET /device_pos_report/{trackerId}}.
 * Tractive liefert die Koordinaten als Array {@code [lat, lon]}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TractivePositionDto(
        List<Double> latlong,
        Double accuracy,
        @JsonProperty("sensor_used") String sensorUsed,
        Long time
) {

    public boolean hasCoordinates() {
        return latlong != null && latlong.size() >= 2
                && latlong.get(0) != null && latlong.get(1) != null;
    }

    public double latitude() {
        return latlong.get(0);
    }

    public double longitude() {
        return latlong.get(1);
    }

    public Instant reportedAt() {
        return time != null ? Instant.ofEpochSecond(time) : null;
    }
}
