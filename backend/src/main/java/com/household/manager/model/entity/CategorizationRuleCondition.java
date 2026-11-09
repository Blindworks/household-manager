package com.household.manager.model.entity;

import jakarta.persistence.*;
import lombok.*;

/** One AND-condition of a categorization rule. */
@Entity
@Table(name = "categorization_rule_condition")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategorizationRuleCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position", nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_field", nullable = false, length = 30)
    private RuleMatchField matchField;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 20)
    private RuleMatchType matchType;

    @Column(name = "pattern", nullable = false, length = 255)
    private String pattern;
}
