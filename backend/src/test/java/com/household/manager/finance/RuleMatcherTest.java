package com.household.manager.finance;

import com.household.manager.model.entity.CategorizationRule;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import com.household.manager.model.entity.Transaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleMatcherTest {

    private final RuleMatcher matcher = new RuleMatcher();

    private Transaction tx(String name, String iban, String purpose) {
        return Transaction.builder()
                .counterpartyName(name).counterpartyIban(iban).purpose(purpose)
                .build();
    }

    private CategorizationRule rule(RuleMatchField field, RuleMatchType type, String pattern) {
        return CategorizationRule.builder()
                .matchField(field).matchType(type).pattern(pattern)
                .categoryId(5L).enabled(true).priority(100).build();
    }

    @Test
    void containsIsCaseInsensitiveOnName() {
        assertTrue(matcher.matches(
                rule(RuleMatchField.COUNTERPARTY_NAME, RuleMatchType.CONTAINS, "netflix"),
                tx("NETFLIX INTERNATIONAL", null, null)));
    }

    @Test
    void equalsRequiresFullMatchIgnoringCase() {
        assertTrue(matcher.matches(
                rule(RuleMatchField.COUNTERPARTY_IBAN, RuleMatchType.EQUALS, "de123"),
                tx(null, "DE123", null)));
        assertFalse(matcher.matches(
                rule(RuleMatchField.COUNTERPARTY_IBAN, RuleMatchType.EQUALS, "de123"),
                tx(null, "DE1234", null)));
    }

    @Test
    void regexMatchesPurpose() {
        assertTrue(matcher.matches(
                rule(RuleMatchField.PURPOSE, RuleMatchType.REGEX, ".*Abo.*"),
                tx(null, null, "Netflix Abo Juni")));
    }

    @Test
    void nullFieldValueNeverMatches() {
        assertFalse(matcher.matches(
                rule(RuleMatchField.COUNTERPARTY_NAME, RuleMatchType.CONTAINS, "x"),
                tx(null, null, null)));
    }

    @Test
    void invalidRegexDoesNotThrowAndReturnsFalse() {
        assertFalse(matcher.matches(
                rule(RuleMatchField.PURPOSE, RuleMatchType.REGEX, "["),
                tx(null, null, "anything")));
    }
}
