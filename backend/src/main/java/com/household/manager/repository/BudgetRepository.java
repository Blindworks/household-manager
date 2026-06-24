package com.household.manager.repository;

import com.household.manager.model.entity.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findByCategoryIdNotNull();

    Optional<Budget> findByCategoryIdIsNull();

    Optional<Budget> findByCategoryId(Long categoryId);
}
