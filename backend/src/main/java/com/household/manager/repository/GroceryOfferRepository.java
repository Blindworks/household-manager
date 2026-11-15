package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryOffer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface GroceryOfferRepository extends JpaRepository<GroceryOffer, Long> {

    /** Bulk-Delete; die Transaktion liefert der Aufrufer (Muster WasteCollectionEventRepository). */
    @Modifying
    @Query("delete from GroceryOffer o where o.validTo is null or o.validTo >= :today")
    int deleteActiveFrom(@Param("today") LocalDate today);
}
