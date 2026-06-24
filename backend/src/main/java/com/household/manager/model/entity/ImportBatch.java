package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Record of a single statement import run.
 */
@Entity
@Table(name = "import_batches")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "filename", length = 255)
    private String filename;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @Column(name = "imported_count", nullable = false)
    private int importedCount;

    @Column(name = "skipped_duplicates", nullable = false)
    private int skippedDuplicates;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    @PrePersist
    protected void onCreate() {
        if (importedAt == null) {
            importedAt = LocalDateTime.now();
        }
    }
}
