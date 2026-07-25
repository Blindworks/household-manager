package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.model.entity.CalendarCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Ein konkretes (bereits expandiertes) Vorkommen. {@code eventId} zeigt immer auf die
 * Master-Zeile (bei Overrides auf die Serie), {@code recurrenceDate} ist der Schluessel
 * fuer die Occurrence-Endpoints; bei Einzelterminen ist er null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarOccurrenceResponse {

    private Long eventId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate occurrenceDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate recurrenceDate;

    private String title;
    private String notes;
    private CalendarCategory category;
    private boolean allDay;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private boolean recurring;

    /** 0 = heute, 1 = morgen. Serverseitig berechnet (Muster WasteCollectionEventResponse). */
    private long daysUntil;
}
