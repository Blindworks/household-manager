package com.household.manager.calendar;

import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.model.entity.CalendarEvent;
import com.household.manager.repository.CalendarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarEventServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Fixes "Jetzt": 25.07.2026, 12:00 Uhr Berliner Zeit (ein Samstag). */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZONE);

    @Mock
    private CalendarEventRepository repository;

    private CalendarEventService service;

    @BeforeEach
    void setUp() {
        service = new CalendarEventService(repository, new RecurrenceExpansionService(), CLOCK);
    }

    private CalendarEventRequest.CalendarEventRequestBuilder validRequest() {
        return CalendarEventRequest.builder()
                .title("Zahnarzt")
                .category(CalendarCategory.HEALTH)
                .allDay(false)
                .startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30));
    }

    @Test
    void createSpeichertUndLiefertResponse() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse response = service.create(validRequest().build());

        assertThat(response.getTitle()).isEqualTo("Zahnarzt");
        assertThat(response.isRecurring()).isFalse();
        verify(repository).save(any(CalendarEvent.class));
    }

    @Test
    void leererTitelWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(validRequest().title("  ").build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Titel");
        verify(repository, never()).save(any());
    }

    @Test
    void uhrzeitTerminOhneStartzeitWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(validRequest().startTime(null).build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Start-Uhrzeit");
    }

    @Test
    void endeVorStartWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(
                validRequest().endTime(LocalTime.of(13, 0)).build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ende");
    }

    @Test
    void enddatumVorStartdatumWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(
                validRequest().allDay(true).startTime(null)
                        .endDate(LocalDate.of(2026, 8, 1)).build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Enddatum");
    }

    @Test
    void ungueltigeRruleWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(validRequest().rrule("FREQ=BANANA").build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Wiederholungsregel");
    }

    @Test
    void zuLangerTitelWirdAbgelehnt() {
        String titelMit201Zeichen = "A".repeat(201);
        assertThatThrownBy(() -> service.create(validRequest().title(titelMit201Zeichen).build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("200 Zeichen");
        verify(repository, never()).save(any());
    }

    @Test
    void ganztagsTerminVerliertUhrzeiten() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse response = service.create(
                validRequest().allDay(true).endTime(LocalTime.of(15, 0)).build());

        assertThat(response.getStartTime()).isNull();
        assertThat(response.getEndTime()).isNull();
    }

    @Test
    void endDateWirdBeiUhrzeitTerminVerworfen() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse response = service.create(
                validRequest().endDate(LocalDate.of(2026, 8, 5)).build());

        assertThat(response.getEndDate()).isNull();
    }

    @Test
    void getEventLiefertStammdatenEinesExistierendenTermins() {
        CalendarEvent event = CalendarEvent.builder()
                .id(4L).title("Zahnarzt").category(CalendarCategory.HEALTH)
                .allDay(false).startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30))
                .build();
        when(repository.findById(4L)).thenReturn(Optional.of(event));

        CalendarEventResponse response = service.getEvent(4L);

        assertThat(response.getId()).isEqualTo(4L);
        assertThat(response.getTitle()).isEqualTo("Zahnarzt");
    }

    @Test
    void getEventMitUnbekannterIdWirft404() {
        when(repository.findById(123L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEvent(123L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deleteLoeschtAuchOverrides() {
        CalendarEvent event = CalendarEvent.builder().id(7L).title("Serie").build();
        when(repository.findById(7L)).thenReturn(Optional.of(event));

        service.delete(7L);

        verify(repository).deleteByRecurringParentId(7L);
        verify(repository).delete(event);
    }

    @Test
    void updateUnbekannterIdLiefert404() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(99L, validRequest().build()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updatePersistiertGeaenderteFelderUndLiefertSieZurueck() {
        CalendarEvent existing = CalendarEvent.builder()
                .id(3L).title("Alt").category(CalendarCategory.HEALTH)
                .allDay(false).startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30))
                .build();
        when(repository.findById(3L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse response = service.update(3L, validRequest().title("Neu").build());

        assertThat(response.getTitle()).isEqualTo("Neu");
        assertThat(existing.getTitle()).isEqualTo("Neu");
    }

    @Test
    void updateMitUnveraenderterRruleLaesstExdatesUndOverridesInRuhe() {
        CalendarEvent existing = CalendarEvent.builder()
                .id(5L).title("Serie").category(CalendarCategory.HEALTH)
                .allDay(false).startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30))
                .rrule("FREQ=DAILY").exdates("2026-08-05")
                .build();
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(5L, validRequest().rrule("FREQ=DAILY").build());

        verify(repository, never()).deleteByRecurringParentId(any());
        assertThat(existing.getExdates()).isEqualTo("2026-08-05");
    }

    @Test
    void updateMitGeaenderterRruleLoeschtOverridesUndSetztExdatesAufNull() {
        CalendarEvent existing = CalendarEvent.builder()
                .id(5L).title("Serie").category(CalendarCategory.HEALTH)
                .allDay(false).startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30))
                .rrule("FREQ=DAILY").exdates("2026-08-05")
                .build();
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(5L, validRequest().rrule("FREQ=WEEKLY").build());

        verify(repository).deleteByRecurringParentId(5L);
        assertThat(existing.getExdates()).isNull();
    }

    @Test
    void updateDasRruleEntferntLoeschtEbenfallsOverridesUndExdates() {
        CalendarEvent existing = CalendarEvent.builder()
                .id(5L).title("Serie").category(CalendarCategory.HEALTH)
                .allDay(false).startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30))
                .rrule("FREQ=DAILY").exdates("2026-08-05")
                .build();
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(5L, validRequest().build());

        verify(repository).deleteByRecurringParentId(5L);
        assertThat(existing.getExdates()).isNull();
    }
}
