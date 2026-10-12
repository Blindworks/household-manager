package com.household.manager.model.entity;

import com.household.manager.agent.*;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
/** Gedaechtnis des Hausverstands. Zeitstempel setzen die Services ueber das Clock-Bean (testbar). */
@Entity
@Table(name = "agent_note")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentNote {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoteKind kind;

    @Column(nullable = false, length = 500)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NoteSource source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
}