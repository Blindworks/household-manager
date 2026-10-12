package com.household.manager.repository;

import com.household.manager.agent.ActionClass;
import com.household.manager.model.entity.AgentActionPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentActionPolicyRepository extends JpaRepository<AgentActionPolicyEntity, ActionClass> {
}