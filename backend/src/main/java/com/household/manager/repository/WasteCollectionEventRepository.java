package com.household.manager.repository;

import com.household.manager.model.entity.WasteCollectionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Räumt das Zukunftsfenster für den Resync.
     *
     * <p>Bewusst eine Bulk-DML-Anweisung und kein abgeleiteter Delete: Ein abgeleiteter Delete
     * lädt die Zeilen nur und stellt sie über {@code em.remove()} in die Warteschlange, ohne
     * etwas an die DB zu schicken. Da die Id {@code IDENTITY}-generiert ist, führt Hibernate die
     * nachfolgenden Inserts aber sofort aus — also vor den noch ausstehenden Deletes —, worauf
     * {@code uq_waste_collection_date_label} bei jedem Sync nach dem ersten bricht. Diese
     * Anweisung geht sofort an die DB und stellt die Reihenfolge sicher.
     */
    @Modifying
    @Query("delete from WasteCollectionEvent e where e.collectionDate >= :from")
    void deleteFromDateOnwards(@Param("from") LocalDate from);
}
