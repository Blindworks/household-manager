package com.household.manager.repository;

import com.household.manager.agent.ProposalStatus;
import com.household.manager.model.entity.AgentProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AgentProposalRepository extends JpaRepository<AgentProposal, Long> {

    List<AgentProposal> findByStatus(ProposalStatus status);

    long countByStatus(ProposalStatus status);

    /**
     * Zählt nur zugestellte Zeilen: in der Ruhezeit zurückgehaltene Vorschläge und Nachrichten hat
     * noch niemand gesehen, sie dürfen das Limit offener Vorschläge nicht belegen.
     */
    long countByStatusAndDeliveredAtIsNotNull(ProposalStatus status);

    boolean existsByStatusAndTarget(ProposalStatus status, String target);

    List<AgentProposal> findTop50ByOrderByCreatedAtDesc();

    /**
     * Atomarer Zugriff auf einen offenen Vorschlag: nur wer die Zeile von PENDING weg schaltet, darf
     * ihn ausführen. Zwei gleichzeitige Knopfdrücke (zwei Chats, Admin-Seite) lesen sonst beide PENDING
     * und lösen die Aktion doppelt aus. Liefert 1 für den Gewinner, 0 für jeden weiteren.
     */
    @Transactional
    @Modifying
    @Query("update AgentProposal p set p.status = :to, p.decidedBy = :decidedBy, p.decidedAt = :decidedAt "
            + "where p.id = :id and p.status = :from")
    int claim(@Param("id") long id, @Param("from") ProposalStatus from, @Param("to") ProposalStatus to,
              @Param("decidedBy") String decidedBy, @Param("decidedAt") LocalDateTime decidedAt);

    /**
     * Setzt den Zustellzeitpunkt nur, solange der Vorschlag noch offen ist — ein inzwischen
     * entschiedener darf keine frischen Knöpfe bekommen. 1 = zustellen, 0 = überspringen.
     */
    @Transactional
    @Modifying
    @Query("update AgentProposal p set p.deliveredAt = :now where p.id = :id and p.status = :pending")
    int markDelivered(@Param("id") long id, @Param("pending") ProposalStatus pending,
                      @Param("now") LocalDateTime now);

    List<AgentProposal> findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(
            Collection<ProposalStatus> statuses, LocalDateTime after);
}