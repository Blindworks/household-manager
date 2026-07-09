package com.household.manager.repository;

import com.household.manager.model.entity.Flow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FlowRepository extends JpaRepository<Flow, Long> {

    List<Flow> findAllByOrderByNameAsc();

    List<Flow> findByEnabledTrueAndDeployedDefinitionNotNull();
}
