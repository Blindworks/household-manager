package com.household.manager.finance;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds a stable per-account fingerprint for a transaction so re-imports are idempotent.
 * Prefers the bank's AcctSvcrRef / EndToEndId; otherwise a composite of the core fields.
 */
@Component
public class DedupHasher {

    public String hash(Long accountId, ParsedTransaction tx) {
        String reference = firstNonBlank(tx.getAccountServicerReference(), tx.getEndToEndId());
        String basis = (reference != null)
                ? accountId + "|REF|" + reference
                : String.join("|",
                        String.valueOf(accountId),
                        String.valueOf(tx.getBookingDate()),
                        tx.getAmount().toPlainString(),
                        nullSafe(tx.getCounterpartyIban()),
                        nullSafe(tx.getPurpose()));
        return sha256Hex(basis);
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
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
