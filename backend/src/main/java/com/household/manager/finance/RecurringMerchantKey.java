package com.household.manager.finance;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/** Stable merchant key for recurring-payment detection; looks behind payment intermediaries. */
@Component
@RequiredArgsConstructor
public class RecurringMerchantKey {

    private static final int MAX_LENGTH = 255;

    private final CounterpartyNameNormalizer normalizer;
    private final MerchantNameExtractor merchantExtractor;

    public Optional<String> keyFor(String counterpartyName, String purpose) {
        if (counterpartyName == null || counterpartyName.isBlank()) {
            return Optional.empty();
        }
        Optional<PaymentIntermediary> intermediary = PaymentIntermediary.detect(counterpartyName);
        String key;
        if (intermediary.isPresent()) {
            Optional<String> merchant = merchantExtractor.extract(intermediary.get(), purpose);
            if (merchant.isPresent()) {
                key = PaymentIntermediary.matchedToken(counterpartyName).toUpperCase(Locale.ROOT)
                        + ":" + collapse(merchant.get());
            } else if (intermediary.get() == PaymentIntermediary.AMAZON) {
                // Amazon's purpose format is unknown; amount grouping separates subscriptions from purchases.
                key = normalizer.normalize(counterpartyName);
            } else {
                return Optional.empty();
            }
        } else {
            key = normalizer.normalize(counterpartyName);
        }
        if (key.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(key.length() > MAX_LENGTH ? key.substring(0, MAX_LENGTH) : key);
    }

    private static String collapse(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("\s+", " ").trim();
    }
}
