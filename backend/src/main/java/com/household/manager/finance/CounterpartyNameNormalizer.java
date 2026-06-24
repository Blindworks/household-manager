package com.household.manager.finance;

import org.springframework.stereotype.Component;

/**
 * Normalizes counterparty names so the same merchant maps to a stable, generalizable token.
 * <p>
 * Bank statements (e.g. Sparkasse card payments) format the counterparty as
 * {@code MERCHANT/STREET/CITY/COUNTRY}, often with an embedded store or transaction number
 * (e.g. {@code "REWE SAGT DANKE. 44652184/Rodheimer /Bad Vilbel /DE"}). Keeping the whole
 * string would make a CONTAINS rule match only that single booking. We therefore keep just
 * the merchant segment before the first {@code /}, then strip trailing reference/date noise
 * so that every branch of the same merchant collapses to one token (e.g. {@code "REWE SAGT DANKE"}).
 */
@Component
public class CounterpartyNameNormalizer {

    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toUpperCase();
        value = value.replaceAll("\\s+", " ");
        // Keep only the merchant segment before the first address separator.
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        value = value.trim();
        // Remove trailing groups of long digit runs and dotted dates (store/booking noise).
        value = value.replaceAll("(\\s+(\\d{2}\\.\\d{2}\\.\\d{2,4}|\\d{5,}))+$", "");
        // Drop any leftover trailing punctuation/whitespace (e.g. the "." in "REWE SAGT DANKE.").
        value = value.replaceAll("[\\s.,;:/-]+$", "");
        return value.trim();
    }
}
