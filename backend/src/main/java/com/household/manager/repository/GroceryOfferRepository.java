package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface GroceryOfferRepository extends JpaRepository<GroceryOffer, Long> {

    /** Bulk-Delete; die Transaktion liefert der Aufrufer (Muster WasteCollectionEventRepository). */
    @Modifying
    @Query("delete from GroceryOffer o where o.validTo is null or o.validTo >= :today")
    int deleteActiveFrom(@Param("today") LocalDate today);

    @Query("select o from GroceryOffer o where o.validTo is null or o.validTo >= :today")
    List<GroceryOffer> findActiveFrom(@Param("today") LocalDate today);

    /** Angebote zu Fingerabdrücken (Laufprotokoll: Titel und Preis zu einem Claude-Urteil). */
    List<GroceryOffer> findByFingerprintIn(Collection<String> fingerprints);

    /** Retention: Angebote, deren Gültigkeit vor {@code cutoff} endete. Eigene Transaktion. */
    @Transactional
    @Modifying
    @Query("delete from GroceryOffer o where o.validTo < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDate cutoff);

    /** Retention: Angebote ohne Enddatum, die vor {@code cutoff} gesammelt wurden. Eigene Transaktion. */
    @Transactional
    @Modifying
    @Query("delete from GroceryOffer o where o.validTo is null and o.collectedAt < :cutoff")
    int deleteUndatedCollectedBefore(@Param("cutoff") LocalDateTime cutoff);
}
