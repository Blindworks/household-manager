package com.household.manager.repository;

import com.household.manager.model.entity.WasteCollectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** Repository für {@link WasteCollectionEvent}. */
@Repository
public interface WasteCollectionEventRepository extends JpaRepository<WasteCollectionEvent, Long> {

    List<WasteCollectionEvent> findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(
            LocalDate from, LocalDate to);

    List<WasteCollectionEvent> findByCollectionDateOrderByLabelAsc(LocalDate date);

    /** Zählt Termine ab einschließlich {@code from} — für die Status-Anzeige. */
    long countByCollectionDateGreaterThanEqual(LocalDate from);

    /** Räumt das Zukunftsfenster für den Resync. */
    void deleteByCollectionDateGreaterThanEqual(LocalDate from);
}
