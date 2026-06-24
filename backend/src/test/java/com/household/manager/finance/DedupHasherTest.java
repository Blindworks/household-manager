package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DedupHasherTest {

    private final DedupHasher hasher = new DedupHasher();

    private ParsedTransaction tx(String e2e, String ref) {
        return ParsedTransaction.builder()
                .bookingDate(LocalDate.of(2026, 6, 1))
                .amount(new BigDecimal("-29.99"))
                .currency("EUR")
                .counterpartyIban("NL00NETFLIX0000001")
                .purpose("Netflix Abo Juni")
                .endToEndId(e2e)
                .accountServicerReference(ref)
                .build();
    }

    @Test
    void usesAccountServicerReferenceWhenPresent() {
        String h = hasher.hash(1L, tx(null, "REF-0001"));
        assertEquals(hasher.hash(1L, tx(null, "REF-0001")), h);
        assertNotEquals(hasher.hash(2L, tx(null, "REF-0001")), h, "different account => different hash");
    }

    @Test
    void fallsBackToCompositeWhenNoReference() {
        String h1 = hasher.hash(1L, tx(null, null));
        String h2 = hasher.hash(1L, tx(null, null));
        assertEquals(h1, h2, "same data must hash identically");
    }

    @Test
    void differentAmountProducesDifferentHash() {
        ParsedTransaction a = tx(null, null);
        ParsedTransaction b = ParsedTransaction.builder()
                .bookingDate(a.getBookingDate()).amount(new BigDecimal("-30.00"))
                .currency("EUR").counterpartyIban(a.getCounterpartyIban())
                .purpose(a.getPurpose()).build();
        assertNotEquals(hasher.hash(1L, a), hasher.hash(1L, b));
    }

    @Test
    void hashIsSha256Hex64Chars() {
        assertEquals(64, hasher.hash(1L, tx(null, "REF-0001")).length());
    }

    @Test
    void fallsBackToEndToEndIdWhenAcctSvcrRefIsNull() {
        String withE2e = hasher.hash(1L, tx("E2E-001", null));
        // Same E2E + same account => same hash
        assertEquals(hasher.hash(1L, tx("E2E-001", null)), withE2e);
        // No references at all => composite hash, must differ
        assertNotEquals(hasher.hash(1L, tx(null, null)), withE2e,
                "composite fallback must differ from E2E-keyed hash");
    }
}
