package com.household.manager.repository;

import com.household.manager.grocery.entity.GroceryMatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroceryMatchRepository extends JpaRepository<GroceryMatch, Long> {
}
