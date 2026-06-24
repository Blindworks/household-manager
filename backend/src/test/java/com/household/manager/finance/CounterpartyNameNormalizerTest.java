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

    @Test
    void keepsMerchantBeforeFirstSlashAndDropsStoreNumber() {
        // Sparkasse card-payment format: MERCHANT/STREET/CITY/COUNTRY with an embedded store id.
        assertEquals("REWE SAGT DANKE",
                normalizer.normalize("REWE SAGT DANKE. 44652184/Rodheimer /Bad Vilbel /DE"));
    }

    @Test
    void differentStoresOfSameMerchantNormalizeIdentically() {
        String a = normalizer.normalize("REWE SAGT DANKE. 44652184/Rodheimer /Bad Vilbel /DE");
        String b = normalizer.normalize("REWE SAGT DANKE. 44652155/Rodheimer /Bad Vilbel /DE");
        String c = normalizer.normalize("REWE SAGT DANKE. 44652377/Am Suedbah/Bad Vilbel /DE");
        assertEquals(a, b);
        assertEquals(a, c);
    }

    @Test
    void keepsMerchantWithoutStoreNumber() {
        assertEquals("LIDL SAGT DANKE",
                normalizer.normalize("LIDL SAGT DANKE/ALTE FRANKFURTER STR. 15/BAD VILBEL/DE"));
        assertEquals("TKMAXX-DE",
                normalizer.normalize("TKMAXX-DE/PETER-MUELLER-STR. 18,/DUESSELDORF/DE"));
    }

    @Test
    void leavesPlainNamesWithoutSlashUnchanged() {
        assertEquals("VATTENFALL EUROPE SALES", normalizer.normalize("VATTENFALL EUROPE SALES"));
        assertEquals("MAINOVA AG", normalizer.normalize("Mainova AG"));
    }
}
