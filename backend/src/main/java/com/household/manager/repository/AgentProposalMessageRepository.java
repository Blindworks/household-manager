package com.household.manager.repository;

import com.household.manager.model.entity.AgentProposalMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentProposalMessageRepository extends JpaRepository<AgentProposalMessage, Long> {

    List<AgentProposalMessage> findByProposalId(Long proposalId);
}