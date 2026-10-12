package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

/** Telegram-Nachricht zu einem Vorschlag, damit sie nach der Entscheidung aktualisiert werden kann. */
@Entity
@Table(name = "agent_proposal_message")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentProposalMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(name = "message_id", nullable = false)
    private long messageId;
}