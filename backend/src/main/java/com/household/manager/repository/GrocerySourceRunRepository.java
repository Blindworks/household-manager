package com.household.manager.repository;

import com.household.manager.grocery.entity.GrocerySourceRun;
import com.household.manager.grocery.source.SourceKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrocerySourceRunRepository extends JpaRepository<GrocerySourceRun, SourceKey> {
}
