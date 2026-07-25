package com.household.manager.calendar;

import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.model.entity.CalendarCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarReminderSchedulerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZoneId.of("Europe/Berlin"));

    @Mock
    private CalendarEventService calendarService;
    @Mock
    private EntityStateService entityStateService;

    private CalendarReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CalendarReminderScheduler(calendarService, entityStateService, CLOCK);
    }

    private CalendarOccurrenceResponse occurrence(boolean allDay, LocalTime startTime) {
        return CalendarOccurrenceResponse.builder()
                .eventId(1L).title("Zahnarzt").category(CalendarCategory.HEALTH)
                .allDay(allDay).occurrenceDate(LocalDate.of(2026, 7, 25))
                .startTime(startTime)
                .build();
    }

    @Test
    void feuertUhrzeitTerminImFensterMitKategorieAlsAction() {
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(occurrence(false, LocalTime.of(14, 30))));

        scheduler.fireRemindersBetween(
                LocalDateTime.of(2026, 7, 25, 14, 29),
                LocalDateTime.of(2026, 7, 25, 14, 30));

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService).reportEvent(captor.capture());
        assertThat(captor.getValue().entityId()).isEqualTo("event.calendar_reminder");
        assertThat(captor.getValue().state()).isEqualTo("health");
        assertThat(captor.getValue().attributes()).containsEntry("title", "Zahnarzt");
    }

    @Test
    void feuertGanztaegigenTerminUmAcht() {
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(occurrence(true, null)));

        scheduler.fireRemindersBetween(
                LocalDateTime.of(2026, 7, 25, 7, 59),
                LocalDateTime.of(2026, 7, 25, 8, 0));

        verify(entityStateService).reportEvent(any());
    }

    @Test
    void feuertNichtAusserhalbDesFensters() {
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(occurrence(false, LocalTime.of(14, 30))));

        scheduler.fireRemindersBetween(
                LocalDateTime.of(2026, 7, 25, 14, 30),
                LocalDateTime.of(2026, 7, 25, 14, 31));

        // Startzeit 14:30 lag am Fensteranfang (exklusiv) — bereits im vorigen Lauf gefeuert
        verify(entityStateService, never()).reportEvent(any());
    }
}
