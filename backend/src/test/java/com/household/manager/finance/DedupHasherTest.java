package com.household.manager.finance;

import com.household.manager.model.entity.Transaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DedupHasherTest {

    private final DedupHasher hasher = new DedupHasher();

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private ParsedTransaction parsedSalary(String endToEndId, LocalDate bookingDate, BigDecimal amount) {
        return ParsedTransaction.builder()
                .bookingDate(bookingDate)
                .amount(amount)
                .currency("EUR")
                .counterpartyIban("DE89370400440532013000")
                .purpose("Gehalt")
                .endToEndId(endToEndId)
                .build();
    }

    private Transaction entityFrom(Long accountId, ParsedTransaction tx) {
        return Transaction.builder()
                .accountId(accountId)
                .bookingDate(tx.getBookingDate())
                .amount(tx.getAmount())
                .currency(tx.getCurrency())
                .counterpartyIban(tx.getCounterpartyIban())
                .purpose(tx.getPurpose())
                .endToEndId(tx.getEndToEndId())
                .manuallyCategorized(false)
                .build();
    }

    // ---------------------------------------------------------------------------
    // Regression: the salary collapse bug
    // Two rows share the SAME endToEndId "B2004D" but different bookingDate.
    // They MUST produce DIFFERENT hashes.
    // ---------------------------------------------------------------------------

    @Test
    void recurringPaymentsWithSameEndToEndIdButDifferentDateProduceDifferentHashes() {
        ParsedTransaction january = parsedSalary("B2004D", LocalDate.of(2026, 1, 28), new BigDecimal("3500.00"));
        ParsedTransaction february = parsedSalary("B2004D", LocalDate.of(2026, 2, 28), new BigDecimal("3500.00"));

        String hashJan = hasher.hash(1L, january);
        String hashFeb = hasher.hash(1L, february);

        assertNotEquals(hashJan, hashFeb,
                "Two salary rows with the same endToEndId but different bookingDate must NOT collapse to the same hash");
    }

    // ---------------------------------------------------------------------------
    // Idempotency: identical fields => identical hash
    // ---------------------------------------------------------------------------

    @Test
    void identicalFieldsProduceIdenticalHash() {
        ParsedTransaction a = parsedSalary("B2004D", LocalDate.of(2026, 3, 28), new BigDecimal("3500.00"));
        ParsedTransaction b = parsedSalary("B2004D", LocalDate.of(2026, 3, 28), new BigDecimal("3500.00"));

        assertEquals(hasher.hash(1L, a), hasher.hash(1L, b),
                "Re-importing the exact same booking must yield the same hash");
    }

    // ---------------------------------------------------------------------------
    // Sensitivity: amount and account isolation
    // ---------------------------------------------------------------------------

    @Test
    void differentAmountProducesDifferentHash() {
        ParsedTransaction base = parsedSalary("B2004D", LocalDate.of(2026, 3, 28), new BigDecimal("3500.00"));
        ParsedTransaction raised = parsedSalary("B2004D", LocalDate.of(2026, 3, 28), new BigDecimal("3600.00"));

        assertNotEquals(hasher.hash(1L, base), hasher.hash(1L, raised),
                "Different amount must produce different hash");
    }

    @Test
    void differentAccountProducesDifferentHash() {
        ParsedTransaction tx = parsedSalary("B2004D", LocalDate.of(2026, 3, 28), new BigDecimal("3500.00"));

        assertNotEquals(hasher.hash(1L, tx), hasher.hash(2L, tx),
                "Same booking for different account must produce different hash");
    }

    // ---------------------------------------------------------------------------
    // Recompute consistency: hash(accountId, ParsedTransaction) == hash(accountId, Transaction)
    // ---------------------------------------------------------------------------

    @Test
    void parsedTransactionHashEqualsEntityHash() {
        ParsedTransaction parsed = parsedSalary("B2004D", LocalDate.of(2026, 3, 28), new BigDecimal("3500.00"));
        Transaction entity = entityFrom(1L, parsed);

        assertEquals(hasher.hash(1L, parsed), hasher.hash(entity.getAccountId(), entity),
                "Import-time hash and recompute hash must be identical for the same logical booking");
    }

    // ---------------------------------------------------------------------------
    // Format: SHA-256 hex is always 64 characters
    // ---------------------------------------------------------------------------

    @Test
    void hashIsSha256Hex64Chars() {
        ParsedTransaction tx = parsedSalary("B2004D", LocalDate.of(2026, 3, 28), new BigDecimal("3500.00"));
        assertEquals(64, hasher.hash(1L, tx).length());
    }

    // ---------------------------------------------------------------------------
    // Null safety: null optional fields must not throw
    // ---------------------------------------------------------------------------

    @Test
    void nullOptionalFieldsAreHandledGracefully() {
        ParsedTransaction tx = ParsedTransaction.builder()
                .bookingDate(LocalDate.of(2026, 4, 1))
                .amount(new BigDecimal("100.00"))
                .currency("EUR")
                .build(); // counterpartyIban, purpose, endToEndId all null

        assertDoesNotThrow(() -> hasher.hash(1L, tx));
        assertEquals(64, hasher.hash(1L, tx).length());
    }
}
