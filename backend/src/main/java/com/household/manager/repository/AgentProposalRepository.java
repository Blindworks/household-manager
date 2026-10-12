package com.household.manager.repository;

import com.household.manager.agent.ProposalStatus;
import com.household.manager.model.entity.AgentProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AgentProposalRepository extends JpaRepository<AgentProposal, Long> {

    List<AgentProposal> findByStatus(ProposalStatus status);

    long countByStatus(ProposalStatus status);

    boolean existsByStatusAndTarget(ProposalStatus status, String target);

    List<AgentProposal> findTop50ByOrderByCreatedAtDesc();

    List<AgentProposal> findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            Collection<ProposalStatus> statuses, LocalDateTime after);
}