package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A detected (or confirmed) recurring payment such as rent or a subscription.
 */
@Entity
@Table(name = "recurring_payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** Normalized counterparty name the recurrence is grouped by. */
    @Column(name = "counterparty_pattern", nullable = false, length = 255)
    private String counterpartyPattern;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "expected_amount", precision = 15, scale = 2)
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "interval_kind", nullable = false, length = 20)
    private RecurrenceInterval interval;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    /** Date of the latest booking of the detected series; null for legacy rows no run finds. */
    @Column(name = "last_seen_date")
    private LocalDate lastSeenDate;

    @Column(name = "confirmed", nullable = false)
    private boolean confirmed;

    /** Dismissed rows stay stored so later runs keep matching them, but are never listed. */
    @Column(name = "dismissed", nullable = false)
    private boolean dismissed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
