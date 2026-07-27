package com.household.manager.calendar;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.CalendarEventRequest;
import com.household.manager.dto.CalendarEventResponse;
import com.household.manager.dto.CalendarOccurrenceResponse;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.model.entity.CalendarEvent;
import com.household.manager.repository.CalendarCategoryRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarEventServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Fixes "Jetzt": 25.07.2026, 12:00 Uhr Berliner Zeit (ein Samstag). */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-25T10:00:00Z"), ZONE);

    /** Die einzige Kategorie dieser Tests; ihre Id steht in allen Testdaten. */
    private static final CalendarCategory HEALTH = CalendarCategory.builder()
            .id(3L).key("health").name("Gesundheit").color("#e57373").sortOrder(3).active(true).build();

    @Mock
    private CalendarEventRepository repository;
    @Mock
    private CalendarCategoryRepository categoryRepository;
    @Mock
    private AuditService auditService;

    private CalendarEventService service;

    @BeforeEach
    void setUp() {
        service = new CalendarEventService(repository, categoryRepository,
                new RecurrenceExpansionService(), CLOCK, auditService);
        lenient().when(categoryRepository.existsById(3L)).thenReturn(true);
        lenient().when(categoryRepository.findAll()).thenReturn(List.of(HEALTH));
        lenient().when(categoryRepository.findById(3L)).thenReturn(Optional.of(HEALTH));
    }

    private CalendarEventRequest.CalendarEventRequestBuilder validRequest() {
        return CalendarEventRequest.builder()
                .title("Zahnarzt")
                .categoryId(3L)
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
        // Die Antwort traegt die Kategorie eingebettet, nicht nur ihre Id.
        assertThat(response.getCategory().key()).isEqualTo("health");
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
    void fehlendeKategorieWirdAbgelehnt() {
        assertThatThrownBy(() -> service.create(validRequest().categoryId(null).build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Kategorie");
        verify(repository, never()).save(any());
    }

    @Test
    void unbekannteKategorieWirdAbgelehnt() {
        // Seit die Kategorie kein Enum mehr ist, ist diese Pruefung die einzige Verteidigung
        // davor, dass ein Fremdschluesselfehler als 500er beim Aufrufer landet.
        when(categoryRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(validRequest().categoryId(99L).build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("existiert nicht");
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
    void wiederholungsendeVorStartdatumWirdAbgelehnt() {
        // validRequest() startet am 03.08.2026 - UNTIL liegt vorher, die Serie haette
        // also nie ein Vorkommen.
        assertThatThrownBy(() -> service.create(
                validRequest().rrule("FREQ=WEEKLY;UNTIL=20260701").build()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Ende der Wiederholung");
        verify(repository, never()).save(any());
    }

    @Test
    void gueltigeSerieMitWiederholungsendeNachStartdatumWirdAngenommen() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventResponse response = service.create(
                validRequest().rrule("FREQ=WEEKLY;UNTIL=20261231").build());

        assertThat(response.isRecurring()).isTrue();
        verify(repository).save(any(CalendarEvent.class));
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
                .id(4L).title("Zahnarzt").categoryId(3L)
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
                .id(3L).title("Alt").categoryId(3L)
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
                .id(5L).title("Serie").categoryId(3L)
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
                .id(5L).title("Serie").categoryId(3L)
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
                .id(5L).title("Serie").categoryId(3L)
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

    private CalendarEvent series(Long id, String title, LocalDate start, String rrule) {
        return CalendarEvent.builder()
                .id(id).title(title).categoryId(3L)
                .allDay(true).startDate(start).rrule(rrule)
                .build();
    }

    @Test
    void einzelterminErscheintImFenster() {
        CalendarEvent single = CalendarEvent.builder()
                .id(1L).title("Zahnarzt").categoryId(3L)
                .allDay(false).startDate(LocalDate.of(2026, 8, 3))
                .startTime(LocalTime.of(14, 30))
                .build();
        when(repository.findAll()).thenReturn(List.of(single));

        var result = service.getOccurrences(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo(1L);
        assertThat(result.get(0).getRecurrenceDate()).isNull();
        assertThat(result.get(0).getDaysUntil()).isEqualTo(9);
        assertThat(result.get(0).getCategory().key()).isEqualTo("health");
    }

    @Test
    void serieWirdExpandiertUndExdateGefiltert() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        weekly.addExdate(LocalDate.of(2026, 7, 13));
        when(repository.findAll()).thenReturn(List.of(weekly));

        var result = service.getOccurrences(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(result).extracting(o -> o.getOccurrenceDate()).containsExactly(
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 27));
        assertThat(result).allMatch(o -> o.isRecurring());
        assertThat(result.get(0).getRecurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 6));
    }

    @Test
    void overrideErsetztDasBerechneteVorkommen() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        CalendarEvent override = CalendarEvent.builder()
                .id(3L).title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .recurringParentId(2L).recurrenceDate(LocalDate.of(2026, 7, 13))
                .build();
        when(repository.findAll()).thenReturn(List.of(weekly, override));

        var result = service.getOccurrences(LocalDate.of(2026, 7, 12), LocalDate.of(2026, 7, 18));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Sport (verschoben)");
        assertThat(result.get(0).getOccurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        // eventId zeigt auf die Serie, recurrenceDate auf das ersetzte Vorkommen
        assertThat(result.get(0).getEventId()).isEqualTo(2L);
        assertThat(result.get(0).getRecurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 13));
    }

    @Test
    void upcomingFiltertHeuteBereitsVergangeneUhrzeitTermine() {
        // CLOCK steht auf 25.07.2026 12:00
        CalendarEvent past = CalendarEvent.builder()
                .id(4L).title("Vorbei").categoryId(3L)
                .allDay(false).startDate(LocalDate.of(2026, 7, 25))
                .startTime(LocalTime.of(9, 0))
                .build();
        CalendarEvent later = CalendarEvent.builder()
                .id(5L).title("Kommt noch").categoryId(3L)
                .allDay(false).startDate(LocalDate.of(2026, 7, 25))
                .startTime(LocalTime.of(18, 0))
                .build();
        when(repository.findAll()).thenReturn(List.of(past, later));

        var result = service.getUpcoming(3);

        assertThat(result).extracting(o -> o.getTitle()).containsExactly("Kommt noch");
    }

    @Test
    void upcomingRespektiertDasLimit() {
        CalendarEvent daily = series(6L, "Taeglich", LocalDate.of(2026, 7, 20), "FREQ=DAILY");
        when(repository.findAll()).thenReturn(List.of(daily));

        assertThat(service.getUpcoming(3)).hasSize(3);
    }

    @Test
    void fensterUeberEinemJahrWirdAbgelehnt() {
        assertThatThrownBy(() -> service.getOccurrences(
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 1)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void fensterMitToVorFromWirdAbgelehnt() {
        assertThatThrownBy(() -> service.getOccurrences(
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void overrideAusserhalbDesFenstersLaesstEineLueckeOffen() {
        CalendarEvent weekly = series(10L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        // Verschoben auf einen Termin weit ausserhalb des unten abgefragten Fensters.
        CalendarEvent override = CalendarEvent.builder()
                .id(11L).title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 8, 1))
                .recurringParentId(10L).recurrenceDate(LocalDate.of(2026, 7, 13))
                .build();
        when(repository.findAll()).thenReturn(List.of(weekly, override));

        // Enthaelt genau das per Override ersetzte Vorkommen (07-13) und sonst nichts.
        var result = service.getOccurrences(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 16));

        assertThat(result).isEmpty();
    }

    @Test
    void exdateUndOverrideAufDerselbenSerieWirkenUnabhaengigVoneinander() {
        CalendarEvent weekly = series(12L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        weekly.addExdate(LocalDate.of(2026, 7, 6));
        CalendarEvent override = CalendarEvent.builder()
                .id(13L).title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 15))
                .recurringParentId(12L).recurrenceDate(LocalDate.of(2026, 7, 13))
                .build();
        when(repository.findAll()).thenReturn(List.of(weekly, override));

        var result = service.getOccurrences(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

        assertThat(result).extracting(o -> o.getOccurrenceDate()).containsExactly(
                LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 27));
        assertThat(result).extracting(o -> o.getTitle()).containsExactly(
                "Sport (verschoben)", "Sport", "Sport");
    }

    @Test
    void mehrtaegigeTermineZeigenNurDenStarttagMitPassendemEnddatum() {
        CalendarEvent trip = CalendarEvent.builder()
                .id(20L).title("Urlaub").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 8, 10))
                .endDate(LocalDate.of(2026, 8, 12))
                .build();
        CalendarEvent weekendSeries = CalendarEvent.builder()
                .id(21L).title("Wochenendtrip").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 8, 1))
                .endDate(LocalDate.of(2026, 8, 2)).rrule("FREQ=WEEKLY")
                .build();
        when(repository.findAll()).thenReturn(List.of(trip, weekendSeries));

        var result = service.getOccurrences(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15));

        var urlaub = result.stream().filter(o -> o.getTitle().equals("Urlaub")).findFirst().orElseThrow();
        assertThat(urlaub.getOccurrenceDate()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(urlaub.getEndDate()).isEqualTo(LocalDate.of(2026, 8, 12));

        var wochenendtrips = result.stream()
                .filter(o -> o.getTitle().equals("Wochenendtrip")).toList();
        assertThat(wochenendtrips).extracting(o -> o.getOccurrenceDate()).containsExactly(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 15));
        // Die Dauer (1 Tag) wandert relativ zum jeweiligen Vorkommen-Datum mit.
        assertThat(wochenendtrips).extracting(o -> o.getEndDate()).containsExactly(
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 16));
        // Kein Spanning ueber mehrere Tage im Raster: pro Termin genau ein Eintrag.
        assertThat(result).hasSize(4);
    }

    @Test
    void upcomingMitNichtPositivemLimitLiefertLeereListe() {
        assertThat(service.getUpcoming(0)).isEmpty();
        assertThat(service.getUpcoming(-1)).isEmpty();
    }

    @Test
    void deleteOccurrenceBeiEinzelterminLoeschtDenTermin() {
        CalendarEvent single = CalendarEvent.builder()
                .id(1L).title("Einmalig").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 8, 3))
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(single));

        service.deleteOccurrence(1L, LocalDate.of(2026, 8, 3));

        verify(repository).delete(single);
    }

    @Test
    void deleteOccurrenceBeiSerieTraegtExdateEinUndLoeschtOverride() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        CalendarEvent override = CalendarEvent.builder()
                .id(3L).recurringParentId(2L).recurrenceDate(LocalDate.of(2026, 7, 13))
                .title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .build();
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.findByRecurringParentIdAndRecurrenceDate(2L, LocalDate.of(2026, 7, 13)))
                .thenReturn(Optional.of(override));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteOccurrence(2L, LocalDate.of(2026, 7, 13));

        verify(repository).delete(override);
        assertThat(weekly.exdateSet()).contains(LocalDate.of(2026, 7, 13));
        verify(repository).save(weekly);
    }

    @Test
    void updateOccurrenceLegtOverrideAn() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.findByRecurringParentIdAndRecurrenceDate(2L, LocalDate.of(2026, 7, 13)))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventRequest request = CalendarEventRequest.builder()
                .title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .rrule("FREQ=WEEKLY") // muss ignoriert werden — Overrides sind nie Serien
                .build();

        CalendarOccurrenceResponse response =
                service.updateOccurrence(2L, LocalDate.of(2026, 7, 13), request);

        assertThat(response.getTitle()).isEqualTo("Sport (verschoben)");
        // Vertrag mit dem Frontend: eventId zeigt auf die Serie (Master), nicht auf die
        // eigene Override-Zeilen-Id; recurrenceDate ist das ersetzte Vorkommen.
        assertThat(response.getEventId()).isEqualTo(2L);
        assertThat(response.getRecurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 13));
    }

    @Test
    void updateOccurrenceAufEinzelterminWirdAbgelehnt() {
        CalendarEvent single = CalendarEvent.builder()
                .id(1L).title("Einmalig").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 8, 3))
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(single));

        assertThatThrownBy(() -> service.updateOccurrence(1L, LocalDate.of(2026, 8, 3),
                validRequest().build()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deleteOccurrenceUeberOverrideIdSetztExdateAmMasterUndLoeschtOverride() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        CalendarEvent override = CalendarEvent.builder()
                .id(3L).recurringParentId(2L).recurrenceDate(LocalDate.of(2026, 7, 13))
                .title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .build();
        when(repository.findById(3L)).thenReturn(Optional.of(override));
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteOccurrence(3L, LocalDate.of(2026, 7, 13));

        verify(repository).delete(override);
        verify(repository).save(weekly);
        assertThat(weekly.exdateSet()).contains(LocalDate.of(2026, 7, 13));
    }

    @Test
    void deleteOccurrenceBeiEinzelterminMitFalschemDatumWirdAbgelehnt() {
        CalendarEvent single = CalendarEvent.builder()
                .id(1L).title("Einmalig").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 8, 3))
                .build();
        when(repository.findById(1L)).thenReturn(Optional.of(single));

        assertThatThrownBy(() -> service.deleteOccurrence(1L, LocalDate.of(2026, 8, 4)))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void updateOccurrenceMitDatumAusserhalbDerSerieWirdAbgelehnt() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));

        // 2026-07-10 ist ein Freitag, die woechentliche Serie trifft nur Montage.
        assertThatThrownBy(() -> service.updateOccurrence(2L, LocalDate.of(2026, 7, 10),
                validRequest().build()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateOccurrenceAufZuvorPerExdateGeloeschtemDatumEntferntDasExdate() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        weekly.addExdate(LocalDate.of(2026, 7, 13));
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.findByRecurringParentIdAndRecurrenceDate(2L, LocalDate.of(2026, 7, 13)))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventRequest request = CalendarEventRequest.builder()
                .title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .build();

        service.updateOccurrence(2L, LocalDate.of(2026, 7, 13), request);

        assertThat(weekly.exdateSet()).doesNotContain(LocalDate.of(2026, 7, 13));
    }

    @Test
    void updateOccurrenceAufBestehendemOverrideAktualisiertIhnStattEinenZweitenAnzulegen() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        CalendarEvent existingOverride = CalendarEvent.builder()
                .id(3L).recurringParentId(2L).recurrenceDate(LocalDate.of(2026, 7, 13))
                .title("Sport (fruehere Aenderung)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 13))
                .build();
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.findByRecurringParentIdAndRecurrenceDate(2L, LocalDate.of(2026, 7, 13)))
                .thenReturn(Optional.of(existingOverride));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventRequest request = CalendarEventRequest.builder()
                .title("Sport (neu verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 15))
                .build();

        CalendarOccurrenceResponse response =
                service.updateOccurrence(2L, LocalDate.of(2026, 7, 13), request);

        assertThat(response.getTitle()).isEqualTo("Sport (neu verschoben)");
        assertThat(existingOverride.getTitle()).isEqualTo("Sport (neu verschoben)");
        assertThat(response.getEventId()).isEqualTo(2L);
        assertThat(response.getRecurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        verify(repository).save(existingOverride);
    }

    @Test
    void updateOccurrenceMitUngueltigerRruleImRequestFunktioniertTrotzdem() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.findByRecurringParentIdAndRecurrenceDate(2L, LocalDate.of(2026, 7, 13)))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventRequest request = CalendarEventRequest.builder()
                .title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .rrule("FREQ=BANANA") // ungueltig, muss aber ignoriert werden
                .build();

        CalendarOccurrenceResponse response =
                service.updateOccurrence(2L, LocalDate.of(2026, 7, 13), request);

        assertThat(response.getTitle()).isEqualTo("Sport (verschoben)");
        assertThat(response.getEventId()).isEqualTo(2L);
        assertThat(response.getRecurrenceDate()).isEqualTo(LocalDate.of(2026, 7, 13));
    }

    @Test
    void overrideWirdBeimAendernDerSeriesRruleUeberUpdateEntfernt() {
        CalendarEvent weekly = series(2L, "Sport", LocalDate.of(2026, 7, 6), "FREQ=WEEKLY");
        when(repository.findById(2L)).thenReturn(Optional.of(weekly));
        when(repository.findByRecurringParentIdAndRecurrenceDate(2L, LocalDate.of(2026, 7, 13)))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CalendarEventRequest overrideRequest = CalendarEventRequest.builder()
                .title("Sport (verschoben)").categoryId(3L)
                .allDay(true).startDate(LocalDate.of(2026, 7, 14))
                .build();
        service.updateOccurrence(2L, LocalDate.of(2026, 7, 13), overrideRequest);

        service.update(2L, validRequest().rrule("FREQ=MONTHLY").build());

        verify(repository).deleteByRecurringParentId(2L);
    }
}
