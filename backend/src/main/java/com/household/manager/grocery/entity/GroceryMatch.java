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

/** Urteil, ob ein Angebot (über seinen Fingerabdruck) zu einem Produkt passt. */
@Entity
@Table(name = "grocery_match")
@Getter
@Setter
@NoArgsConstructor
public class GroceryMatch {

    public enum Verdict { MATCH, MAYBE, NO }

    public enum DecidedBy { CLAUDE, USER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Verdict verdict;

    @Column(name = "reason_kind", length = 20)
    private String reasonKind;

    @Column(length = 200)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "decided_by", nullable = false, length = 10)
    private DecidedBy decidedBy;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;
}
