package com.household.manager.grocery.entity;

import com.household.manager.grocery.source.SourceKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Ergebnis des letzten Abrufs einer Quelle. */
@Entity
@Table(name = "grocery_source_run")
@Getter
@Setter
@NoArgsConstructor
public class GrocerySourceRun {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "source_key", length = 20)
    private SourceKey sourceKey;

    @Column(name = "last_run_at", nullable = false)
    private LocalDateTime lastRunAt;

    @Column(name = "offer_count", nullable = false)
    private int offerCount;

    @Column(nullable = false)
    private boolean ok;

    @Column(length = 300)
    private String reason;
}
