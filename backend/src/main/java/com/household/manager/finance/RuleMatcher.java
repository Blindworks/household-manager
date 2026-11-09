package com.household.manager.finance;

import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.CategorizationRuleCondition;
import com.household.manager.model.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Decides whether a categorization rule applies to a transaction. */
@Component
@Slf4j
public class RuleMatcher {

    /** A rule matches when it has conditions and all of them match. */
    public boolean matches(CategorizationRule rule, Transaction tx) {
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            return false;
        }
        return rule.getConditions().stream().allMatch(c -> matches(c, tx));
    }

    public boolean matches(CategorizationRuleCondition condition, Transaction tx) {
        String value = fieldValue(condition, tx);
        if (value == null) {
            return false;
        }
        String haystack = value.toLowerCase();
        String needle = condition.getPattern() == null ? "" : condition.getPattern().toLowerCase();

        return switch (condition.getMatchType()) {
            case CONTAINS -> haystack.contains(needle);
            case EQUALS -> haystack.equals(needle);
            case REGEX -> regexMatches(condition.getPattern(), value);
        };
    }

    private String fieldValue(CategorizationRuleCondition condition, Transaction tx) {
        return switch (condition.getMatchField()) {
            case COUNTERPARTY_NAME -> tx.getCounterpartyName();
            case COUNTERPARTY_IBAN -> tx.getCounterpartyIban();
            case PURPOSE -> tx.getPurpose();
        };
    }

    private boolean regexMatches(String pattern, String value) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                    .matcher(value).matches();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex in categorization rule: {}", pattern);
            return false;
        }
    }
}
