package com.household.manager.petsupply;

import com.household.manager.model.entity.PetSupplyTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Request-/Response-Records der Vorrats-API. */
public final class PetSupplyDtos {

    private PetSupplyDtos() {
    }

    /**
     * Ein Vorrat samt abgeleiteter Kennzahlen. {@code step} ist das Eingaberaster
     * (Futter 0,5 / Tabletten 1) und steuert im Frontend die Zahlenfelder;
     * {@code perDay} macht die Reichweite nachvollziehbar.
     */
    public record SupplyResponse(
            String key,
            String name,
            String unit,
            BigDecimal amountRemaining,
            BigDecimal targetAmount,
            BigDecimal step,
            BigDecimal perDay,
            int percent,
            int daysRemaining
    ) {
    }

    public record TransactionResponse(
            LocalDateTime occurredAt,
            PetSupplyTransaction.Type type,
            BigDecimal amount,
            BigDecimal amountAfter,
            String note
    ) {
        public static TransactionResponse from(PetSupplyTransaction tx) {
            return new TransactionResponse(tx.getOccurredAt(), tx.getType(),
                    tx.getAmount(), tx.getAmountAfter(), tx.getNote());
        }
    }

    public record PurchaseRequest(BigDecimal amount, String note) {
    }

    public record CorrectionRequest(BigDecimal amountRemaining, String note) {
    }

    public record TargetRequest(BigDecimal targetAmount) {
    }
}
