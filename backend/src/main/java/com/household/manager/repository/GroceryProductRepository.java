package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroceryProductRepository extends JpaRepository<GroceryProduct, Long> {

    List<GroceryProduct> findByActiveTrue();
}
