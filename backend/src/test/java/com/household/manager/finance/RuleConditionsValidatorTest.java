package com.household.manager.finance;

import com.household.manager.dto.RuleConditionDto;
import com.household.manager.model.entity.RuleMatchField;
import com.household.manager.model.entity.RuleMatchType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuleConditionsValidatorTest {

    private RuleConditionDto cond(String pattern) {
        return RuleConditionDto.builder().field(RuleMatchField.PURPOSE)
                .matchType(RuleMatchType.CONTAINS).pattern(pattern).build();
    }

    private void assertRejected(List<RuleConditionDto> conditions) {
        assertThrows(IllegalArgumentException.class, () -> RuleConditionsValidator.validate(conditions));
    }

    @Test
    void emptyListIsRejected() {
        assertRejected(List.of());
        assertRejected(null);
    }

    @Test
    void sixConditionsAreRejected() {
        assertRejected(Collections.nCopies(6, cond("x")));
    }

    @Test
    void blankPatternIsRejected() {
        assertRejected(List.of(cond("  ")));
    }

    @Test
    void invalidRegexIsRejected() {
        assertRejected(List.of(RuleConditionDto.builder().field(RuleMatchField.PURPOSE)
                .matchType(RuleMatchType.REGEX).pattern("(").build()));
    }

    @Test
    void tooLongPatternIsRejected() {
        assertRejected(List.of(cond("a".repeat(256))));
    }

    @Test
    void missingFieldOrTypeIsRejected() {
        assertRejected(List.of(RuleConditionDto.builder().matchType(RuleMatchType.CONTAINS).pattern("x").build()));
        assertRejected(List.of(RuleConditionDto.builder().field(RuleMatchField.PURPOSE).pattern("x").build()));
    }

    @Test
    void fiveValidConditionsPass() {
        assertDoesNotThrow(() -> RuleConditionsValidator.validate(Collections.nCopies(5, cond("x"))));
    }
}
