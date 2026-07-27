package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

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
    private CalendarCategoryView category;
    private boolean allDay;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private boolean recurring;

    /** Zugeordnete Personen; leer = Haushaltstermin. */
    private List<CalendarPersonView> persons;

    /** 0 = heute, 1 = morgen. Serverseitig berechnet (Muster WasteCollectionEventResponse). */
    private long daysUntil;
}
