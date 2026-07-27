package com.household.manager.calendar;

import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.dto.CalendarCategoryView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarReminderSchedulerTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDate DATE = LocalDate.of(2026, 7, 25);
    private static final Clock CLOCK = Clock.fixed(berlin(2026, 7, 25, 10, 0), BERLIN);

    @Mock
    private CalendarEventService calendarService;
    @Mock
    private EntityStateService entityStateService;

    private CalendarReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CalendarReminderScheduler(calendarService, entityStateService, CLOCK);
    }

    /** Wandzeit in Europe/Berlin als Instant, so wie die Produktion sie auch berechnet. */
    private static Instant berlin(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(BERLIN).toInstant();
    }

    private CalendarOccurrenceResponse occurrence(boolean allDay, LocalTime startTime) {
        return occurrenceOn(DATE, allDay, startTime);
    }

    private CalendarOccurrenceResponse occurrenceOn(LocalDate date, boolean allDay, LocalTime startTime) {
        return CalendarOccurrenceResponse.builder()
                .eventId(1L).title("Zahnarzt")
                .category(new CalendarCategoryView(3L, "health", "Gesundheit", "#e57373", null))
                .allDay(allDay).occurrenceDate(date)
                .startTime(startTime)
                .build();
    }

    @Test
    void feuertUhrzeitTerminImFensterMitKategorieAlsAction() {
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(occurrence(false, LocalTime.of(14, 30))));

        scheduler.fireRemindersBetween(
                berlin(2026, 7, 25, 14, 29),
                berlin(2026, 7, 25, 14, 30));

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
                berlin(2026, 7, 25, 7, 59),
                berlin(2026, 7, 25, 8, 0));

        verify(entityStateService).reportEvent(any());
    }

    @Test
    void feuertNichtAusserhalbDesFensters() {
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(occurrence(false, LocalTime.of(14, 30))));

        scheduler.fireRemindersBetween(
                berlin(2026, 7, 25, 14, 30),
                berlin(2026, 7, 25, 14, 31));

        // Startzeit 14:30 lag am Fensteranfang (exklusiv) — bereits im vorigen Lauf gefeuert
        verify(entityStateService, never()).reportEvent(any());
    }

    @Test
    void mehrereFaelligeTermineImSelbenFensterFeuernAlle() {
        CalendarOccurrenceResponse first = occurrenceOn(DATE, false, LocalTime.of(14, 10));
        CalendarOccurrenceResponse second = occurrenceOn(DATE, true, null);
        when(calendarService.getOccurrences(any(), any())).thenReturn(List.of(first, second));

        scheduler.fireRemindersBetween(
                berlin(2026, 7, 25, 7, 0),
                berlin(2026, 7, 25, 15, 0));

        verify(entityStateService, times(2)).reportEvent(any());
    }

    @Test
    void terminOhneStartzeitLaesstLaufNichtAbstuerzenUndFeuertAlsFallbackUmAcht() {
        CalendarOccurrenceResponse missingStartTime = occurrenceOn(DATE, false, null);
        when(calendarService.getOccurrences(any(), any())).thenReturn(List.of(missingStartTime));

        assertThatCode(() -> scheduler.fireRemindersBetween(
                berlin(2026, 7, 25, 7, 59),
                berlin(2026, 7, 25, 8, 0)))
                .doesNotThrowAnyException();

        verify(entityStateService).reportEvent(any());
    }

    @Test
    void zweiAufeinanderfolgendeLaeufeFeuernLueckenlosOhneDoppelung() {
        MutableClock clock = new MutableClock(berlin(2026, 7, 25, 10, 0), BERLIN);
        CalendarReminderScheduler tickingScheduler =
                new CalendarReminderScheduler(calendarService, entityStateService, clock);

        CalendarOccurrenceResponse firstWindowOccurrence = occurrenceOn(DATE, false, LocalTime.of(10, 20));
        CalendarOccurrenceResponse secondWindowOccurrence = occurrenceOn(DATE, false, LocalTime.of(10, 40));
        when(calendarService.getOccurrences(any(), any()))
                .thenReturn(List.of(firstWindowOccurrence, secondWindowOccurrence));

        clock.advanceTo(berlin(2026, 7, 25, 10, 30));
        tickingScheduler.checkDueReminders();

        clock.advanceTo(berlin(2026, 7, 25, 11, 0));
        tickingScheduler.checkDueReminders();

        ArgumentCaptor<EntityStateUpdate> captor = ArgumentCaptor.forClass(EntityStateUpdate.class);
        verify(entityStateService, times(2)).reportEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(u -> u.attributes().get("time"))
                .containsExactly("10:20", "10:40");
    }

    @Test
    void ausnahmeInGetOccurrencesReisstDenSchedulerNichtUndLastCheckedSchreitetFort() {
        MutableClock clock = new MutableClock(berlin(2026, 7, 25, 10, 0), BERLIN);
        CalendarReminderScheduler tickingScheduler =
                new CalendarReminderScheduler(calendarService, entityStateService, clock);

        when(calendarService.getOccurrences(any(), any()))
                .thenThrow(new RuntimeException("Kalender nicht erreichbar"))
                .thenReturn(List.of(occurrenceOn(DATE, false, LocalTime.of(10, 40))));

        clock.advanceTo(berlin(2026, 7, 25, 10, 30));
        assertThatCode(tickingScheduler::checkDueReminders).doesNotThrowAnyException();
        verify(entityStateService, never()).reportEvent(any());

        clock.advanceTo(berlin(2026, 7, 25, 11, 0));
        tickingScheduler.checkDueReminders();

        // lastChecked wurde trotz Ausnahme auf 10:30 fortgeschrieben, daher feuert der
        // Folgelauf fuer das Fenster (10:30, 11:00] normal.
        verify(entityStateService).reportEvent(any());
    }

    @Test
    void erinnerungInDerWiederholtenOktoberstundeFeuertNurEinmal() {
        // Wandzeit 02:30 existiert am 25.10.2026 zweimal (Sommerzeit- und Winterzeit-Instant).
        // atZone() loest die Mehrdeutigkeit auf die fruehere (Sommerzeit-)Instant auf.
        Instant reminderInstant = LocalDateTime.of(2026, 10, 25, 2, 30).atZone(BERLIN).toInstant();

        MutableClock clock = new MutableClock(reminderInstant.minus(Duration.ofMinutes(5)), BERLIN);
        CalendarReminderScheduler tickingScheduler =
                new CalendarReminderScheduler(calendarService, entityStateService, clock);

        CalendarOccurrenceResponse occurrence =
                occurrenceOn(LocalDate.of(2026, 10, 25), false, LocalTime.of(2, 30));
        when(calendarService.getOccurrences(any(), any())).thenReturn(List.of(occurrence));

        // Lauf 1: ueberstreicht die (Sommerzeit-)Instant des Erinnerungszeitpunkts -> feuert.
        clock.advanceTo(reminderInstant.plus(Duration.ofMinutes(5)));
        tickingScheduler.checkDueReminders();

        // Lauf 2: reale Zeit laeuft weiter durch die wiederholte Wanduhrzeit 02:30 (jetzt
        // Winterzeit) hindurch -> darf NICHT nochmal feuern, die reminderInstant liegt bereits
        // vor dem neuen Fensteranfang.
        clock.advanceTo(reminderInstant.plus(Duration.ofMinutes(65)));
        tickingScheduler.checkDueReminders();

        verify(entityStateService, times(1)).reportEvent(any());
    }

    /** Testbare Clock mit veraenderbarem Instant, um Scheduler-Laeufe ueber Zeit hinweg zu simulieren. */
    private static final class MutableClock extends Clock {
        private final ZoneId zone;
        private Instant instant;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advanceTo(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
