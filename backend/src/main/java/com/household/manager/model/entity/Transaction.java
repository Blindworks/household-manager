package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single booked transaction imported from a bank statement.
 * Amount is signed: negative = expense (debit), positive = income (credit).
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "booking_date", nullable = false)
    private LocalDate bookingDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    /** Signed amount: negative = expense, positive = income. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "counterparty_name", length = 255)
    private String counterpartyName;

    @Column(name = "counterparty_iban", length = 34)
    private String counterpartyIban;

    @Column(name = "purpose", columnDefinition = "TEXT")
    private String purpose;

    @Column(name = "end_to_end_id", length = 255)
    private String endToEndId;

    @Column(name = "bank_tx_code", length = 100)
    private String bankTxCode;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "recurring_id")
    private Long recurringId;

    /** True if a user set the category by hand; protects it from auto-rules. */
    @Column(name = "manually_categorized", nullable = false)
    private boolean manuallyCategorized;

    @Column(name = "import_batch_id")
    private Long importBatchId;

    /** Unique fingerprint used to skip duplicate imports. */
    @Column(name = "dedup_hash", nullable = false, length = 64, unique = true)
    private String dedupHash;

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
