package com.household.manager.calendar;

import com.household.manager.dto.CalendarPersonView;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.CalendarEventPerson;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.CalendarEventPersonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarEventPersonServiceTest {

    private static final AppUser ANNA = AppUser.builder()
            .id(2L).username("anna").displayName("Anna").enabled(true).build();

    @Mock
    private CalendarEventPersonRepository repository;
    @Mock
    private AppUserRepository userRepository;

    @InjectMocks
    private CalendarEventPersonService service;

    private CalendarEventPerson link(Long eventId, Long userId) {
        return CalendarEventPerson.builder().calendarEventId(eventId).userId(userId).build();
    }

    @SuppressWarnings("unchecked")
    private List<CalendarEventPerson> capturedSaveAll() {
        ArgumentCaptor<List<CalendarEventPerson>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        return saved.getValue();
    }

    // --- validate -------------------------------------------------------------------------

    @Test
    void weistUnbekanntePersonenAb() {
        when(userRepository.findAllById(List.of(99L))).thenReturn(List.of());

        assertThatThrownBy(() -> service.validate(List.of(99L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("99");
    }

    @Test
    void bekanntePersonenPassierenDiePruefung() {
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(ANNA));

        service.validate(List.of(2L));
    }

    @Test
    void ohneZugeordnetePersonenWirdNichtsGeprueft() {
        // Der Normalfall (Haushaltstermin) darf keine Abfrage kosten.
        service.validate(null);
        service.validate(List.of());

        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void ignoriertNullEintraegeInDerPersonenliste() {
        // Ein null im JSON-Array darf nicht bis zu findAllById durchschlagen — Spring Data
        // wirft dort eine InvalidDataAccessApiUsageException, also einen 500er.
        service.validate(Arrays.asList((Long) null));

        verify(userRepository, never()).findAllById(any());
    }

    // --- replace --------------------------------------------------------------------------

    @Test
    void legtNeueZuordnungenAnUndFasstDuplikateZusammen() {
        when(repository.findByCalendarEventId(1L)).thenReturn(List.of());
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(ANNA));

        List<CalendarPersonView> views = service.replace(1L, List.of(2L, 2L));

        // Eine doppelt geschickte Id darf nicht zu zwei Zeilen fuehren — der
        // Primaerschluessel wuerde das mit einem 500er quittieren.
        assertThat(capturedSaveAll()).hasSize(1);
        assertThat(capturedSaveAll().get(0).getCalendarEventId()).isEqualTo(1L);
        assertThat(capturedSaveAll().get(0).getUserId()).isEqualTo(2L);
        assertThat(views).extracting(CalendarPersonView::displayName).containsExactly("Anna");
    }

    @Test
    void entferntNichtMehrZugeordnetePersonen() {
        CalendarEventPerson existing = link(1L, 2L);
        when(repository.findByCalendarEventId(1L)).thenReturn(List.of(existing));

        assertThat(service.replace(1L, List.of())).isEmpty();

        verify(repository).deleteAll(List.of(existing));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void laesstUnveraenderteZuordnungenUnberuehrt() {
        when(repository.findByCalendarEventId(1L)).thenReturn(List.of(link(1L, 2L)));
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(ANNA));

        service.replace(1L, List.of(2L));

        // Denselben Schluessel in einer Transaktion zu loeschen und sofort wieder
        // anzulegen waere eine Wette auf Hibernates Flush-Reihenfolge (Inserts laufen
        // vor Deletes) — eine unveraenderte Zuordnung bleibt deshalb einfach stehen.
        verify(repository, never()).deleteAll(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void ignoriertNullEintraegeBeimSchreiben() {
        when(repository.findByCalendarEventId(1L)).thenReturn(List.of());
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(ANNA));

        List<CalendarPersonView> views = service.replace(1L, Arrays.asList(null, 2L, null));

        assertThat(capturedSaveAll()).hasSize(1);
        assertThat(capturedSaveAll().get(0).getUserId()).isEqualTo(2L);
        // Die null-Eintraege duerfen weder eine Zeile noch einen Eintrag in der Antwort
        // erzeugen — die echte Person daneben aber sehr wohl.
        assertThat(views).extracting(CalendarPersonView::displayName).containsExactly("Anna");
    }

    // --- Leseformen -----------------------------------------------------------------------

    @Test
    void viewsForLiefertDiePersonenEinesTermins() {
        when(repository.findByCalendarEventId(1L)).thenReturn(List.of(link(1L, 2L)));
        when(userRepository.findAllById(List.of(2L))).thenReturn(List.of(ANNA));

        assertThat(service.viewsFor(1L))
                .extracting(CalendarPersonView::displayName).containsExactly("Anna");
    }

    @Test
    void viewsForOhneZuordnungLiefertEineLeereListe() {
        when(repository.findByCalendarEventId(1L)).thenReturn(List.of());

        assertThat(service.viewsFor(1L)).isEmpty();
    }

    @Test
    void viewsByEventGruppiertNachTerminId() {
        when(repository.findAll()).thenReturn(List.of(link(1L, 2L), link(7L, 2L)));
        when(userRepository.findAll()).thenReturn(List.of(ANNA));

        Map<Long, List<CalendarPersonView>> views = service.viewsByEvent();

        assertThat(views.get(1L)).extracting(CalendarPersonView::displayName)
                .containsExactly("Anna");
        assertThat(views.get(7L)).extracting(CalendarPersonView::displayName)
                .containsExactly("Anna");
    }

    @Test
    void laesstZuordnungenOhneNutzerWeg() {
        when(repository.findAll()).thenReturn(List.of(link(1L, 99L)));
        when(userRepository.findAll()).thenReturn(List.of(ANNA));

        // Der Fremdschluessel schliesst das aus; faellt er je weg, ist eine ausgelassene
        // Person besser als ein Chip ohne Namen im Monatsraster.
        assertThat(service.viewsByEvent().get(1L)).isEmpty();
    }
}
