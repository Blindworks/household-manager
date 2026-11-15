package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroceryProductRepository extends JpaRepository<GroceryProduct, Long> {
}
