package com.household.manager.grocery.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BasePriceParserTest {

    private static BasePrice bp(String amount, BaseUnit unit) {
        return new BasePrice(new BigDecimal(amount), unit);
    }

    @Test
    void fromTextReadsLiterPrices() {
        assertThat(BasePriceParser.fromText("(1 l = 1.33)")).contains(bp("1.330", BaseUnit.LITER));
        assertThat(BasePriceParser.fromText("1 l = 0,79 €")).contains(bp("0.790", BaseUnit.LITER));
        assertThat(BasePriceParser.fromText("l-Preis 1.35")).contains(bp("1.350", BaseUnit.LITER));
        assertThat(BasePriceParser.fromText("1 L = 1,00")).contains(bp("1.000", BaseUnit.LITER));
        assertThat(BasePriceParser.fromText("(1 l = 0,95 €)")).contains(bp("0.950", BaseUnit.LITER));
    }

    @Test
    void fromTextReadsKilogramAndNormalizesGrams() {
        assertThat(BasePriceParser.fromText("(1 kg = 4,99)")).contains(bp("4.990", BaseUnit.KILOGRAM));
        assertThat(BasePriceParser.fromText("100 g = 0,89")).contains(bp("8.900", BaseUnit.KILOGRAM));
    }

    @Test
    void fromTextNormalizesMilliliters() {
        assertThat(BasePriceParser.fromText("100 ml = 0,50")).contains(bp("5.000", BaseUnit.LITER));
    }

    @Test
    void fromTextReadsPieceOnlyWhenExplicit() {
        assertThat(BasePriceParser.fromText("1 Stück = 0,35")).contains(bp("0.350", BaseUnit.PIECE));
        assertThat(BasePriceParser.fromText("ab 2 Stück")).isEmpty();
    }

    @Test
    void fromPackagingComputesLiterPrice() {
        assertThat(BasePriceParser.fromPackaging("je 18 x 0,33 l", new BigDecimal("8.49")))
                .contains(bp("1.429", BaseUnit.LITER));
        assertThat(BasePriceParser.fromPackaging("je 6 x 1-l-Fl.-Kasten zzgl. 2.40 Pfand", new BigDecimal("7.99")))
                .contains(bp("1.332", BaseUnit.LITER));
        assertThat(BasePriceParser.fromPackaging("je 1,25-l-Fl.", new BigDecimal("1.19")))
                .contains(bp("0.952", BaseUnit.LITER));
        assertThat(BasePriceParser.fromPackaging("je Ka. 12 x 1-l-PET-Fl.", new BigDecimal("10.99")))
                .contains(bp("0.916", BaseUnit.LITER));
        assertThat(BasePriceParser.fromPackaging("je 9x 0,5 l zzgl. 2.25 Pfand", new BigDecimal("4.49")))
                .contains(bp("0.998", BaseUnit.LITER));
    }

    @Test
    void fromPackagingComputesKilogramPrice() {
        assertThat(BasePriceParser.fromPackaging("500-g-Packg.", new BigDecimal("2.49")))
                .contains(bp("4.980", BaseUnit.KILOGRAM));
    }

    @Test
    void dotWithThreeDigitsBeforeGramsIsAThousandsSeparator() {
        // Penny: "je 1.000 g" ergab 12990 €/kg statt 12,99 €/kg
        assertThat(BasePriceParser.fromPackaging("je 1.000 g", new BigDecimal("12.99")))
                .contains(bp("12.990", BaseUnit.KILOGRAM));
        assertThat(BasePriceParser.fromText("1.000 g = 12,99")).contains(bp("12.990", BaseUnit.KILOGRAM));
        assertThat(BasePriceParser.fromPackaging("je 1.5 l", new BigDecimal("1.50")))
                .contains(bp("1.000", BaseUnit.LITER));
    }

    @Test
    void depositIsNeverAddedToThePrice() {
        assertThat(BasePriceParser.fromPackaging("je 6 x 1 l", new BigDecimal("6.00")))
                .isEqualTo(BasePriceParser.fromPackaging("je 6 x 1 l zzgl. 3,30 € Pfand", new BigDecimal("6.00")));
    }

    @Test
    void unsureInputYieldsEmpty() {
        assertThat(BasePriceParser.fromPackaging("versch. Sorten", new BigDecimal("1.99"))).isEmpty();
        assertThat(BasePriceParser.fromPackaging("je 2 x 250 g + 1 x 1 l", new BigDecimal("5.0"))).isEmpty();
        assertThat(BasePriceParser.fromPackaging("je 6 x 1 l", null)).isEmpty();
        assertThat(BasePriceParser.fromPackaging(null, new BigDecimal("1.0"))).isEmpty();
        assertThat(BasePriceParser.fromText(null)).isEmpty();
    }

    @Test
    void resolvePrefersStructuredThenTextThenPackagingThenDescription() {
        BasePrice structured = bp("9.000", BaseUnit.LITER);
        BigDecimal price = new BigDecimal("2.00");
        assertThat(BasePriceParser.resolve(structured, "1 l = 1,00", "1 l", "1 l", price)).contains(structured);
        assertThat(BasePriceParser.resolve(null, "1 l = 1,00", "2 l", "4 l", price))
                .contains(bp("1.000", BaseUnit.LITER));
        assertThat(BasePriceParser.resolve(null, "ab 2 Stück", "2 l", "4 l", price))
                .contains(bp("1.000", BaseUnit.LITER));
        assertThat(BasePriceParser.resolve(null, null, null, "4 l", price)).contains(bp("0.500", BaseUnit.LITER));
        assertThat(BasePriceParser.resolve(null, null, null, null, price)).isEqualTo(Optional.empty());
    }

    @Test
    void baseTextInsideDescriptionIsNotTakenAsPackSize() {
        BigDecimal price = new BigDecimal("0.99");
        assertThat(BasePriceParser.resolve(null, null, null, "versch. Sorten (1 l = 0.66)", price))
                .contains(bp("0.660", BaseUnit.LITER));
        assertThat(BasePriceParser.resolve(null, null, null, "je 1,5-l-Fl. (1 l = 0.66)", new BigDecimal("0.99")))
                .contains(bp("0.660", BaseUnit.LITER));
        assertThat(BasePriceParser.fromPackaging("versch. Sorten (1 l = 0.66)", price)).isEmpty();
    }

    @Test
    void multiplicationSignIsAMultiplier() {
        assertThat(BasePriceParser.fromPackaging("6 × 0,33 l", new BigDecimal("1.98")))
                .contains(bp("1.000", BaseUnit.LITER));
        assertThat(BasePriceParser.fromPackaging("je 2 x 250 g", new BigDecimal("3.98")))
                .contains(bp("7.960", BaseUnit.KILOGRAM));
    }

    @Test
    void otherMultipackNotationsYieldEmpty() {
        BigDecimal price = new BigDecimal("1.98");
        assertThat(BasePriceParser.fromPackaging("6er-Pack 0,33 l", price)).isEmpty();
        assertThat(BasePriceParser.fromPackaging("24 Fl. à 0,33 l", price)).isEmpty();
    }
}
