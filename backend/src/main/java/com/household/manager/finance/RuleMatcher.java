package com.household.manager.finance;

import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Decides whether a categorization rule applies to a transaction. */
@Component
@Slf4j
public class RuleMatcher {

    public boolean matches(CategorizationRule rule, Transaction tx) {
        String value = fieldValue(rule, tx);
        if (value == null) {
            return false;
        }
        String haystack = value.toLowerCase();
        String needle = rule.getPattern() == null ? "" : rule.getPattern().toLowerCase();

        return switch (rule.getMatchType()) {
            case CONTAINS -> haystack.contains(needle);
            case EQUALS -> haystack.equals(needle);
            case REGEX -> regexMatches(rule.getPattern(), value);
        };
    }

    private String fieldValue(CategorizationRule rule, Transaction tx) {
        return switch (rule.getMatchField()) {
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
