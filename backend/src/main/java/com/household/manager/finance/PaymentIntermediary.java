package com.household.manager.finance;

import com.household.manager.dto.RuleConditionDto;
import com.household.manager.model.entity.RuleMatchField;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Payment intermediaries whose bookings hide the real merchant behind their own name. */
public enum PaymentIntermediary {
    PAYPAL("PayPal", "PAYPAL"),
    AMAZON("Amazon", "AMAZON", "AMZN");

    private final String displayName;
    private final List<String> tokens;

    PaymentIntermediary(String displayName, String... tokens) {
        this.displayName = displayName;
        this.tokens = List.of(tokens);
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<PaymentIntermediary> detect(String counterpartyName) {
        if (counterpartyName == null) {
            return Optional.empty();
        }
        String upper = counterpartyName.toUpperCase(Locale.ROOT);
        for (PaymentIntermediary i : values()) {
            if (i.tokens.stream().anyMatch(upper::contains)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /** The token that matched, or null when the name is no intermediary. */
    public static String matchedToken(String counterpartyName) {
        if (counterpartyName == null) {
            return null;
        }
        String upper = counterpartyName.toUpperCase(Locale.ROOT);
        for (PaymentIntermediary i : values()) {
            for (String token : i.tokens) {
                if (upper.contains(token)) {
                    return token;
                }
            }
        }
        return null;
    }

    /** Warning for rules that only look at the counterparty name and thus catch every payment of one. */
    public static String broadRuleWarning(List<RuleConditionDto> conditions) {
        if (conditions == null || conditions.isEmpty()
                || !conditions.stream().allMatch(c -> c.getField() == RuleMatchField.COUNTERPARTY_NAME)) {
            return null;
        }
        for (RuleConditionDto c : conditions) {
            Optional<PaymentIntermediary> found = detect(c.getPattern());
            if (found.isPresent()) {
                return "Diese Regel trifft alle " + found.get().displayName() + "-Zahlungen.";
            }
        }
        return null;
    }
}
