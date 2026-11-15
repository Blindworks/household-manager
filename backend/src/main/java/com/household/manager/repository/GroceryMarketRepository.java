package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryMarket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroceryMarketRepository extends JpaRepository<GroceryMarket, Long> {
}
