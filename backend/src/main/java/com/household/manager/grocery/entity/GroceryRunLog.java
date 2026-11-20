package com.household.manager.grocery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Protokoll eines Supermarkt-Laufs: Kennzahlen als Spalten, Quellen und Produkte als JSON in {@code details}. */
@Entity
@Table(name = "grocery_run_log")
@Getter
@Setter
@NoArgsConstructor
public class GroceryRunLog {

    public enum Trigger { NIGHTLY, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10)
    private Trigger trigger;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at", nullable = false)
    private LocalDateTime finishedAt;

    @Column(nullable = false)
    private boolean ok;

    @Column(length = 500)
    private String error;

    @Column(nullable = false)
    private int offers;

    @Column(name = "raw_offers", nullable = false)
    private int rawOffers;

    @Column(nullable = false)
    private int products;

    @Column(name = "ai_calls", nullable = false)
    private int aiCalls;

    @Column(name = "estimate_calls", nullable = false)
    private int estimateCalls;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "ai_failures", nullable = false)
    private int aiFailures;

    @Column(nullable = false)
    private int deferred;

    @Column(name = "new_judgements", nullable = false)
    private int newJudgements;

    @Column(nullable = false)
    private int deals;

    @Column(name = "source_failures", nullable = false)
    private int sourceFailures;

    @Column(columnDefinition = "LONGTEXT")
    private String details;
}
