package com.household.manager.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.household.manager.model.entity.CalendarCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** Stammdaten eines Termins/einer Serie, wie der Termindialog sie laedt. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalendarEventResponse {

    private Long id;
    private String title;
    private String notes;
    private CalendarCategory category;
    private boolean allDay;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private String rrule;
    private boolean recurring;
}
