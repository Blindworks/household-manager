package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface GroceryProductRepository extends JpaRepository<GroceryProduct, Long> {

    List<GroceryProduct> findByActiveTrue();

    /**
     * Schreibt nur die Normalpreis-Schätzung, ohne {@code updated_at} und ohne die übrigen Felder: der Lauf darf
     * eine zwischenzeitliche Bearbeitung des Produkts nicht mit seiner alten Kopie überschreiben.
     */
    @Transactional
    @Modifying
    @Query("update GroceryProduct p set p.estimatedPricePerUnit = :price, p.estimatedAt = :at where p.id = :id")
    int updateEstimate(@Param("id") long id, @Param("price") BigDecimal price, @Param("at") LocalDateTime at);
}
