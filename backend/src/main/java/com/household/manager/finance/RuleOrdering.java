package com.household.manager.finance;

import com.household.manager.model.entity.CategorizationRule;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Single definition of rule evaluation order: more specific first, then priority, then id. */
public final class RuleOrdering {

    public static final Comparator<CategorizationRule> COMPARATOR = Comparator
            .comparingInt((CategorizationRule r) -> -conditionCount(r))
            .thenComparingInt(CategorizationRule::getPriority)
            .thenComparing(CategorizationRule::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private RuleOrdering() {
    }

    public static List<CategorizationRule> sort(Collection<CategorizationRule> rules) {
        return rules.stream().sorted(COMPARATOR).toList();
    }

    private static int conditionCount(CategorizationRule rule) {
        return rule.getConditions() == null ? 0 : rule.getConditions().size();
    }
}
