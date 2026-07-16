package com.household.manager.service;

import com.household.manager.dto.WasteCollectionEventResponse;
import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WasteCollectionServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** Fixer "Jetzt"-Zeitpunkt: 16.07.2026, 10:00 Uhr Berliner Zeit. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-16T08:00:00Z"), ZONE);

    @Mock
    private WasteCollectionEventRepository repository;

    private WasteCollectionService service;

    @BeforeEach
    void setUp() {
        service = new WasteCollectionService(repository, CLOCK);
    }

    private WasteCollectionEvent event(LocalDate date, String label) {
        return WasteCollectionEvent.builder().collectionDate(date).label(label).build();
    }

    @Test
    void fensterVonDreiTagenReichtBisUebermorgen() {
        when(repository.findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 18)))
                .thenReturn(List.of(event(LocalDate.of(2026, 7, 17), "Biotonne")));

        List<WasteCollectionEventResponse> result = service.getUpcoming(3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getLabel()).isEqualTo("Biotonne");
        assertThat(result.get(0).getDaysUntil()).isEqualTo(1);
    }

    @Test
    void berechnetDaysUntilRelativZurUhr() {
        when(repository.findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 16)))
                .thenReturn(List.of(event(LocalDate.of(2026, 7, 16), "Restmuell")));

        assertThat(service.getUpcoming(1).get(0).getDaysUntil()).isZero();
    }

    @Test
    void hebtZuKleinesFensterAufEinenTagAn() {
        when(repository.findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 16)))
                .thenReturn(List.of());

        service.getUpcoming(0);

        // Ein Fenster von 0 Tagen wuerde sonst ein leeres Intervall abfragen.
    }

    @Test
    void liefertLabelsFuerMorgen() {
        when(repository.findByCollectionDateOrderByLabelAsc(LocalDate.of(2026, 7, 17)))
                .thenReturn(List.of(
                        event(LocalDate.of(2026, 7, 17), "Biotonne"),
                        event(LocalDate.of(2026, 7, 17), "Restmuell")));

        assertThat(service.getLabelsForTomorrow()).containsExactly("Biotonne", "Restmuell");
    }

    @Test
    void zaehltBekannteTermineAbHeute() {
        when(repository.countByCollectionDateGreaterThanEqual(LocalDate.of(2026, 7, 16)))
                .thenReturn(7L);

        assertThat(service.countUpcoming()).isEqualTo(7L);
    }

    @Test
    void heuteIstDerErsteTagDesFensters() {
        assertThat(service.today()).isEqualTo(LocalDate.of(2026, 7, 16));
    }
}
