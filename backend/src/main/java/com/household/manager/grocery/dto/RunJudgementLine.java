package com.household.manager.grocery.dto;

import java.math.BigDecimal;

/**
 * Ein in diesem Lauf von Claude getroffenes Urteil. Die Angebotsfelder fehlen, wenn das Angebot nicht mehr im
 * Bestand ist (abgelaufen und aufgeräumt).
 */
public record RunJudgementLine(long productId, String productName, String verdict, String reasonKind,
                               String reason, String offerTitle, String chain, BigDecimal price,
                               BigDecimal basePrice, String baseUnit) {
}
