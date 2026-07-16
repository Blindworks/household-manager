package com.household.manager.service;

import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Ersetzt das Zukunftsfenster der Abholtermine in einer Transaktion.
 *
 * <p>Bewusst eine eigene Bean und nicht eine Methode des Polling-Service: Der Bulk-Delete
 * braucht eine aktive Transaktion, und {@code @Transactional} wirkt nur ueber den
 * Spring-Proxy — bei einem Selbstaufruf innerhalb derselben Bean bliebe die Annotation
 * wirkungslos. Zudem umschliesst die Transaktion so nur Delete und Insert, waehrend der
 * HTTP-Abruf ausserhalb bleibt und keine Verbindung blockiert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WasteCollectionResyncService {

    private final WasteCollectionEventRepository repository;

    /**
     * Loescht alle Termine ab {@code from} und schreibt {@code parsed} neu. Vergangenes bleibt
     * als Historie stehen.
     */
    @Transactional
    public void resync(LocalDate from, List<ParsedWasteEvent> parsed) {
        repository.deleteFromDateOnwards(from);
        List<WasteCollectionEvent> entities = deduplicate(parsed).stream()
                .map(event -> WasteCollectionEvent.builder()
                        .collectionDate(event.date())
                        .label(event.label())
                        .build())
                .toList();
        repository.saveAll(entities);
        log.debug("Resync ab {}: {} Termine geschrieben", from, entities.size());
    }

    /** Der Unique-Constraint (collection_date, label) duldet keine Dubletten aus dem ICS. */
    private List<ParsedWasteEvent> deduplicate(List<ParsedWasteEvent> parsed) {
        return List.copyOf(new LinkedHashSet<>(parsed));
    }
}
