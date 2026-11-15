package com.household.manager.repository;

import com.household.manager.grocery.Chain;
import com.household.manager.grocery.entity.GroceryMarket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroceryMarketRepository extends JpaRepository<GroceryMarket, Long> {

    Optional<GroceryMarket> findByChainAndExternalId(Chain chain, String externalId);

    List<GroceryMarket> findBySelectedTrue();
}
