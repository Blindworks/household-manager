package com.household.manager.petfood;

import com.household.manager.model.entity.PetFoodTransaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Request-/Response-Records der Futtervorrats-API. */
public final class PetFoodDtos {

    private PetFoodDtos() {
    }

    public record StatusResponse(
            BigDecimal cansRemaining,
            BigDecimal targetCans,
            int percent,
            int daysRemaining
    ) {
    }

    public record TransactionResponse(
            LocalDateTime occurredAt,
            PetFoodTransaction.Type type,
            BigDecimal amount,
            BigDecimal cansAfter,
            String note
    ) {
        public static TransactionResponse from(PetFoodTransaction tx) {
            return new TransactionResponse(tx.getOccurredAt(), tx.getType(),
                    tx.getAmount(), tx.getCansAfter(), tx.getNote());
        }
    }

    public record PurchaseRequest(BigDecimal cans, String note) {
    }

    public record CorrectionRequest(BigDecimal cansRemaining, String note) {
    }

    public record TargetRequest(BigDecimal targetCans) {
    }
}
