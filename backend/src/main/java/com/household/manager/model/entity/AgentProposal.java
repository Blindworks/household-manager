package com.household.manager.model.entity;

import com.household.manager.agent.*;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
/** Vorschlag des Hausverstands, der auf Freigabe wartet bzw. entschieden wurde. */
@Entity
@Table(name = "agent_proposal")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentProposal {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_class", nullable = false, length = 20)
    private ActionClass actionClass;

    @Column(nullable = false, length = 200)
    private String target;

    @Column(name = "action_json", nullable = false, columnDefinition = "TEXT")
    private String actionJson;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(nullable = false)
    private boolean urgent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProposalStatus status;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    @Column(length = 1000)
    private String error;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}