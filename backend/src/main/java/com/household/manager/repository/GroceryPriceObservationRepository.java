package com.household.manager.repository;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.entity.GroceryPriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GroceryPriceObservationRepository extends JpaRepository<GroceryPriceObservation, Long> {

    Optional<GroceryPriceObservation> findByProductIdAndChainAndIsoWeek(Long productId, Chain chain, String isoWeek);

    List<GroceryPriceObservation> findByProductIdAndIsoWeekIn(Long productId, Collection<String> isoWeeks);
}
