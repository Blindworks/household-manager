package com.household.manager.repository;

import com.household.manager.model.entity.CategorizationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CategorizationRuleRepository extends JpaRepository<CategorizationRule, Long> {

    List<CategorizationRule> findByEnabledTrueOrderByPriorityAsc();
}
