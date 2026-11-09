package com.household.manager.finance;

import com.household.manager.dto.RuleConditionDto;
import com.household.manager.model.entity.RuleMatchType;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validates the condition list of a categorization rule. */
public final class RuleConditionsValidator {

    public static final int MAX_CONDITIONS = 5;
    public static final int MAX_PATTERN_LENGTH = 255;

    private RuleConditionsValidator() {
    }

    public static void validate(List<RuleConditionDto> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("Eine Regel braucht mindestens eine Bedingung.");
        }
        if (conditions.size() > MAX_CONDITIONS) {
            throw new IllegalArgumentException("Eine Regel darf höchstens " + MAX_CONDITIONS + " Bedingungen haben.");
        }
        for (RuleConditionDto c : conditions) {
            validateOne(c);
        }
    }

    private static void validateOne(RuleConditionDto c) {
        if (c == null || c.getField() == null) {
            throw new IllegalArgumentException("Jede Bedingung braucht ein Feld.");
        }
        if (c.getMatchType() == null) {
            throw new IllegalArgumentException("Jede Bedingung braucht einen Vergleichstyp.");
        }
        if (c.getPattern() == null || c.getPattern().trim().isEmpty()) {
            throw new IllegalArgumentException("Das Muster einer Bedingung darf nicht leer sein.");
        }
        if (c.getPattern().length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException(
                    "Das Muster darf höchstens " + MAX_PATTERN_LENGTH + " Zeichen lang sein.");
        }
        if (c.getMatchType() == RuleMatchType.REGEX) {
            try {
                Pattern.compile(c.getPattern());
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Ungültiger regulärer Ausdruck: " + c.getPattern());
            }
        }
    }
}
