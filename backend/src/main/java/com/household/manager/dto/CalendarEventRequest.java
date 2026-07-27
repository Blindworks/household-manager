package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Anlege-/Aenderungsdaten eines Kalendertermins. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEventRequest {

    private String title;
    private String notes;
    private Long categoryId;
    private boolean allDay;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /** iCal-RRULE; null/leer = Einzeltermin. */
    private String rrule;

    /** Zugeordnete Nutzer; leer oder null = Haushaltstermin. */
    private List<Long> personUserIds;
}
