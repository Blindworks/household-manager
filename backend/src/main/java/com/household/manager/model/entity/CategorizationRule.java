package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A rule that auto-assigns a category when all of its conditions match a transaction.
 */
@Entity
@Table(name = "categorization_rules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategorizationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** All conditions must match (AND); at least one, at most five. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "rule_id", nullable = false)
    @OrderBy("position ASC")
    @Builder.Default
    private List<CategorizationRuleCondition> conditions = new ArrayList<>();

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** Lower number = evaluated first. */
    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

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
