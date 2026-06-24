package com.household.manager.finance;

import com.household.manager.model.entity.Transaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;

/**
 * Builds a stable per-account fingerprint for a transaction so re-imports are idempotent.
 *
 * <p>Always uses a composite of the stable per-booking fields — never a reference-only key.
 * This ensures that recurring payments sharing the same EndToEndId (e.g. monthly salary)
 * each produce a distinct hash as long as their booking date or amount differs.
 *
 * <p>Fields included: accountId | bookingDate | amount | counterpartyIban | purpose | endToEndId
 */
@Component
public class DedupHasher {

    /** Hash at import time from the parsed bank-statement row. */
    public String hash(Long accountId, ParsedTransaction tx) {
        return compositeHash(
                accountId,
                tx.getBookingDate(),
                tx.getAmount(),
                tx.getCounterpartyIban(),
                tx.getPurpose(),
                tx.getEndToEndId());
    }

    /** Hash from a persisted entity — used by the recompute maintenance job. */
    public String hash(Long accountId, Transaction tx) {
        return compositeHash(
                accountId,
                tx.getBookingDate(),
                tx.getAmount(),
                tx.getCounterpartyIban(),
                tx.getPurpose(),
                tx.getEndToEndId());
    }

    private String compositeHash(Long accountId,
                                 LocalDate bookingDate,
                                 BigDecimal amount,
                                 String counterpartyIban,
                                 String purpose,
                                 String endToEndId) {
        String basis = String.join("|",
                String.valueOf(accountId),
                String.valueOf(bookingDate),
                amount.toPlainString(),
                nullSafe(counterpartyIban),
                nullSafe(purpose),
                nullSafe(endToEndId));
        return sha256Hex(basis);
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
