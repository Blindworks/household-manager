package com.household.manager.calendar;

import com.household.manager.audit.AuditService;
import com.household.manager.dto.CalendarCategoryRequest;
import com.household.manager.dto.CalendarCategoryResponse;
import com.household.manager.model.entity.CalendarCategory;
import com.household.manager.repository.CalendarCategoryRepository;
import com.household.manager.repository.CalendarEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarCategoryServiceTest {

    @Mock
    private CalendarCategoryRepository repository;
    @Mock
    private CalendarEventRepository eventRepository;
    @Mock
    private AuditService auditService;

    private CalendarCategoryService service;

    @BeforeEach
    void setUp() {
        service = new CalendarCategoryService(repository, eventRepository,
                new CalendarCategoryKeyGenerator(), auditService);
    }

    private CalendarCategory existing() {
        return CalendarCategory.builder()
                .id(7L).key("arbeit").name("Arbeit").color("#ffb74d")
                .sortOrder(5).active(true).build();
    }

    @Test
    void erzeugtDenSchluesselAusDemNamen() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.create(new CalendarCategoryRequest(
                "Sport & Freizeit", "#4caf50", "pets", 9, true));

        assertThat(response.key()).isEqualTo("sport_freizeit");
    }

    @Test
    void haengtBeiKollisionEineZahlAn() {
        when(repository.findAll()).thenReturn(List.of(existing()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.create(new CalendarCategoryRequest(
                "Arbeit", "#ffb74d", null, 6, true));

        assertThat(response.key()).isEqualTo("arbeit_2");
    }

    @Test
    void laesstDenSchluesselBeimUmbenennenUnveraendert() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.update(7L, new CalendarCategoryRequest(
                "Büro", "#ffb74d", "work", 5, true));

        assertThat(response.key()).isEqualTo("arbeit");
        assertThat(response.name()).isEqualTo("Büro");
    }

    @Test
    void verweigertDasLoeschenEinerGenutztenKategorie() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(eventRepository.countByCategoryId(7L)).thenReturn(4L);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("4")
                .satisfies(thrown -> assertThat(((ResponseStatusException) thrown).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
        verify(repository, never()).delete(any());
    }

    @Test
    void loeschtEineUngenutzteKategorie() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(eventRepository.countByCategoryId(7L)).thenReturn(0L);

        service.delete(7L);

        verify(repository).delete(any());
    }

    @Test
    void deaktivierenLaesstDenSchluesselUndDieTermineUnberuehrt() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.update(7L, new CalendarCategoryRequest(
                "Arbeit", "#ffb74d", null, 5, false));

        assertThat(response.active()).isFalse();
        assertThat(response.key()).isEqualTo("arbeit");
        verify(eventRepository, never()).countByCategoryId(any());
    }

    @Test
    void uebernimmtFarbeIconUndReihenfolgeBeimAendern() {
        when(repository.findById(7L)).thenReturn(Optional.of(existing()));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        CalendarCategoryResponse response = service.update(7L, new CalendarCategoryRequest(
                "Arbeit", "#1e88e5", "work", 12, true));

        assertThat(response.color()).isEqualTo("#1e88e5");
        assertThat(response.icon()).isEqualTo("work");
        assertThat(response.sortOrder()).isEqualTo(12);
    }

    /**
     * Die Admin-Seite und die Auswahlliste verlassen sich auf die Reihenfolge der
     * Kategorien — list() muss die sortierende Abfrage nutzen und darf sie nicht
     * nachtraeglich umsortieren.
     */
    @Test
    void liefertDieKategorienInDerSortierungDesRepositories() {
        CalendarCategory zuerst = CalendarCategory.builder()
                .id(1L).key("muell").name("Muellabfuhr").color("#8d6e63")
                .sortOrder(1).active(true).build();
        when(repository.findAllByOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(zuerst, existing()));

        List<CalendarCategoryResponse> categories = service.list();

        assertThat(categories).extracting(CalendarCategoryResponse::key)
                .containsExactly("muell", "arbeit");
        verify(repository, never()).findAll();
    }

    @Test
    void weistEinenLeerenNamenAb() {
        assertThatThrownBy(() -> service.create(new CalendarCategoryRequest(
                "   ", "#4caf50", null, 1, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Name");
        verify(repository, never()).save(any());
    }

    @Test
    void weistEineUngueltigeFarbeAb() {
        assertThatThrownBy(() -> service.create(new CalendarCategoryRequest(
                "Arbeit", "rot", null, 1, true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Farbe");
    }

    @Test
    void schreibtEinenAuditEintragBeimAnlegen() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.create(new CalendarCategoryRequest("Arbeit", "#ffb74d", null, 1, true));

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(action.capture(), any());
        assertThat(action.getValue()).isEqualTo("calendar-category.create");
    }
}
