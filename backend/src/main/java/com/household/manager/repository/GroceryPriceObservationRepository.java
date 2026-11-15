package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryPriceObservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroceryPriceObservationRepository extends JpaRepository<GroceryPriceObservation, Long> {
}
