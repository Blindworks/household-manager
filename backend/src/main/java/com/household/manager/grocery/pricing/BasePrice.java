package com.household.manager.grocery.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Preis je Bezugseinheit (Liter, Kilogramm, Stück), auf drei Nachkommastellen gerundet. */
public record BasePrice(BigDecimal amount, BaseUnit unit) {

    public BasePrice {
        amount = amount == null ? null : amount.setScale(3, RoundingMode.HALF_UP);
    }
}
