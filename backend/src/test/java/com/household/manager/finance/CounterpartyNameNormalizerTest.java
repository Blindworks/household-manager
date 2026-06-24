package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterpartyNameNormalizerTest {

    private final CounterpartyNameNormalizer normalizer = new CounterpartyNameNormalizer();

    @Test
    void upperCasesAndTrims() {
        assertEquals("NETFLIX", normalizer.normalize("  Netflix  "));
    }

    @Test
    void collapsesWhitespace() {
        assertEquals("REWE MARKT", normalizer.normalize("REWE    Markt"));
    }

    @Test
    void stripsTrailingDigitGroupsAndDates() {
        assertEquals("REWE SAGT DANKE", normalizer.normalize("REWE SAGT DANKE 1234567 01.06.2026"));
    }

    @Test
    void returnsEmptyForNull() {
        assertEquals("", normalizer.normalize(null));
    }
}
