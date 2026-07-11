package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** Gesamtantwort der Luftqualitäts-Kachel (UBA-Luftqualitätsindex). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AirQualityOverviewResponse {

    private String stationId;

    /** Startzeit des jüngsten Messintervalls (CET). */
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime dateTime;

    /** Gesamt-Index: 0 = sehr gut … 4 = sehr schlecht, -1 = keine Daten. */
    private int overallIndex;

    /** true, wenn dem UBA für dieses Intervall Messwerte fehlen. */
    private boolean incomplete;

    private List<AirQualityComponent> components;
}
