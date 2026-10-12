package com.household.manager.model.entity;

import com.household.manager.agent.*;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
/** Protokoll eines Agentenlaufs inklusive Tokenverbrauch (Grundlage fuer Budget und Diagnose). */
@Entity
@Table(name = "agent_run")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentRun {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private AgentTriggerType triggerType;

    @Column(name = "trigger_detail", length = 1000)
    private String triggerDetail;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "tool_calls", nullable = false)
    private int toolCalls;

    @Column(length = 2000)
    private String summary;

    @Column(length = 1000)
    private String error;
}