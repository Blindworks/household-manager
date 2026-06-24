package com.household.manager.finance;

import org.springframework.stereotype.Component;

/**
 * Normalizes counterparty names so the same merchant maps to a stable token:
 * upper-cases, collapses whitespace, and strips trailing reference/date noise.
 */
@Component
public class CounterpartyNameNormalizer {

    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toUpperCase();
        value = value.replaceAll("\\s+", " ");
        // Remove trailing groups of long digit runs and dotted dates (booking noise).
        value = value.replaceAll("(\\s+(\\d{2}\\.\\d{2}\\.\\d{2,4}|\\d{5,}))+$", "");
        return value.trim();
    }
}
