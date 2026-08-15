package com.household.manager.repository;

import com.household.manager.model.entity.PetFoodStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PetFoodStockRepository extends JpaRepository<PetFoodStock, Long> {
}
