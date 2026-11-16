package com.household.manager.repository;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.entity.GroceryPriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroceryPriceObservationRepository extends JpaRepository<GroceryPriceObservation, Long> {

    Optional<GroceryPriceObservation> findByProductIdAndChainAndIsoWeek(Long productId, Chain chain, String isoWeek);

    List<GroceryPriceObservation> findByProductIdAndIsoWeekIn(Long productId, Collection<String> isoWeeks);

    List<GroceryPriceObservation> findByProductIdAndObservedAtGreaterThanEqualOrderByIsoWeekAsc(Long productId,
                                                                                               LocalDateTime from);

    /** Retention: Beobachtungen vor {@code cutoff}. Eigene Transaktion. */
    @Transactional
    @Modifying
    @Query("delete from GroceryPriceObservation o where o.observedAt < :cutoff")
    int deleteObservedBefore(@Param("cutoff") LocalDateTime cutoff);
}
