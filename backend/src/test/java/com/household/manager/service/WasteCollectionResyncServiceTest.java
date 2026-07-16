package com.household.manager.service;

import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WasteCollectionResyncServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 16);

    @Mock
    private WasteCollectionEventRepository repository;

    private WasteCollectionResyncService service;

    @BeforeEach
    void setUp() {
        service = new WasteCollectionResyncService(repository);
    }

    @Test
    void loeschtDasZukunftsfensterUndSchreibtDieNeuenTermine() {
        service.resync(TODAY, List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 24), "Restmuell")));

        // Achtung, begrenzte Aussagekraft: Dieser Mock belegt nur die Aufrufreihenfolge in Java,
        // nicht die Reihenfolge der SQL-Anweisungen. Dass der Delete die DB auch wirklich vor den
        // Inserts erreicht, haengt an Hibernates Flush-Semantik und kann erst ein Test gegen eine
        // echte DB zeigen (ein abgeleiteter Delete war hier gruen und in der Praxis trotzdem kaputt).
        InOrder inOrder = inOrder(repository);
        inOrder.verify(repository).deleteFromDateOnwards(TODAY);
        inOrder.verify(repository).saveAll(any());
    }

    @Test
    void bildetDieTermineKorrektAufEntitaetenAb() {
        service.resync(TODAY, List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne")));

        ArgumentCaptor<List<WasteCollectionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());

        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getCollectionDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(captor.getValue().get(0).getLabel()).isEqualTo("Biotonne");
    }

    @Test
    void entferntDuplikateAusDemIcs() {
        service.resync(TODAY, List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne")));

        // Der Unique-Constraint (collection_date, label) wuerde bei Dubletten brechen.
        ArgumentCaptor<List<WasteCollectionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    void behaeltMehrereTonnenAmSelbenTag() {
        service.resync(TODAY, List.of(
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Biotonne"),
                new ParsedWasteEvent(LocalDate.of(2026, 7, 17), "Restmuell")));

        ArgumentCaptor<List<WasteCollectionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }
}
