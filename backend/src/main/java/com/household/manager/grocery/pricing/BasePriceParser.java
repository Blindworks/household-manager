package com.household.manager.grocery.pricing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Einzige Stelle, die aus Angebotstexten einen normierten Grundpreis (€/l, €/kg, €/Stück) ableitet. */
public final class BasePriceParser {

    private static final String NUMBER = "\\d+(?:[.,]\\d+)?";
    private static final Pattern UNIT_PRICE = Pattern.compile(
            "(?<![\\d.,])(" + NUMBER + ")\\s*(ml|cl|kg|g|l)\\s*=\\s*(" + NUMBER + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIECE_PRICE = Pattern.compile(
            "(?<![\\d.,])(" + NUMBER + ")\\s*(?:Stück|Stueck|St\\.?)\\s*=\\s*(" + NUMBER + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern LABELLED_PRICE = Pattern.compile(
            "\\b(l|kg)\\s*-?\\s*Preis\\s*:?\\s*(" + NUMBER + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPOSIT = Pattern.compile(
            "(?:zzgl\\.?\\s*)?" + NUMBER + "\\s*€?\\s*Pfand", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUANTITY = Pattern.compile(
            "(?<![\\d.,])(?:(\\d+)\\s*[x×]\\s*)?(" + NUMBER + ")\\s*-?\\s*(ml|cl|kg|liter|g|l)(?!\\p{L})",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COUNT_WORD_BEFORE = Pattern.compile(
            "(?:\\d+\\s*(?:er(?:-?pack)?|fl\\.?|flaschen|dosen|stk\\.?|st\\.?)|à|\\ba)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private BasePriceParser() {
    }

    public static Optional<BasePrice> fromText(String basePriceText) {
        if (basePriceText == null || basePriceText.isBlank()) {
            return Optional.empty();
        }
        Matcher unit = UNIT_PRICE.matcher(basePriceText);
        if (unit.find()) {
            return perQuantity(number(unit.group(1)), unit.group(2), number(unit.group(3)));
        }
        Matcher piece = PIECE_PRICE.matcher(basePriceText);
        if (piece.find()) {
            BigDecimal count = number(piece.group(1));
            if (!positive(count)) {
                return Optional.empty();
            }
            return Optional.of(new BasePrice(number(piece.group(2)).divide(count, MathContext.DECIMAL64),
                    BaseUnit.PIECE));
        }
        Matcher labelled = LABELLED_PRICE.matcher(basePriceText);
        if (labelled.find()) {
            BaseUnit target = labelled.group(1).equalsIgnoreCase("l") ? BaseUnit.LITER : BaseUnit.KILOGRAM;
            return Optional.of(new BasePrice(number(labelled.group(2)), target));
        }
        return Optional.empty();
    }

    public static Optional<BasePrice> fromPackaging(String packagingOrDescription, BigDecimal price) {
        if (packagingOrDescription == null || price == null || price.signum() <= 0) {
            return Optional.empty();
        }
        String cleaned = DEPOSIT.matcher(packagingOrDescription).replaceAll(" ");
        cleaned = UNIT_PRICE.matcher(cleaned).replaceAll(" ");
        cleaned = PIECE_PRICE.matcher(cleaned).replaceAll(" ");
        cleaned = LABELLED_PRICE.matcher(cleaned).replaceAll(" ");
        Matcher matcher = QUANTITY.matcher(cleaned);
        if (!matcher.find()) {
            return Optional.empty();
        }
        if (matcher.group(1) == null
                && COUNT_WORD_BEFORE.matcher(cleaned.substring(0, matcher.start())).find()) {
            // Gebindeangabe ("6er-Pack", "24 Fl. à") ohne verlässliche Stückzahl
            return Optional.empty();
        }
        BigDecimal count = matcher.group(1) == null ? BigDecimal.ONE : new BigDecimal(matcher.group(1));
        BigDecimal total = count.multiply(number(matcher.group(2)));
        String unit = matcher.group(3);
        if (matcher.find()) {
            // mehrere Mengenangaben im Text: nicht sicher zuordenbar
            return Optional.empty();
        }
        return perQuantity(total, unit, price);
    }

    public static Optional<BasePrice> resolve(BasePrice structured, String basePriceText, String packaging,
            String description, BigDecimal price) {
        if (structured != null) {
            return Optional.of(structured);
        }
        return fromText(basePriceText)
                .or(() -> fromPackaging(packaging, price))
                .or(() -> fromText(description))
                .or(() -> fromPackaging(description, price));
    }

    private static Optional<BasePrice> perQuantity(BigDecimal quantity, String unit, BigDecimal price) {
        if (!positive(quantity) || price == null) {
            return Optional.empty();
        }
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "l", "liter" -> Optional.of(liter(price.divide(quantity, MathContext.DECIMAL64)));
            case "cl" -> Optional.of(liter(price.multiply(HUNDRED).divide(quantity, MathContext.DECIMAL64)));
            case "ml" -> Optional.of(liter(price.multiply(THOUSAND).divide(quantity, MathContext.DECIMAL64)));
            case "kg" -> Optional.of(new BasePrice(price.divide(quantity, MathContext.DECIMAL64), BaseUnit.KILOGRAM));
            case "g" -> Optional.of(new BasePrice(
                    price.multiply(THOUSAND).divide(quantity, MathContext.DECIMAL64), BaseUnit.KILOGRAM));
            default -> Optional.empty();
        };
    }

    private static BasePrice liter(BigDecimal amount) {
        return new BasePrice(amount, BaseUnit.LITER);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal number(String text) {
        return new BigDecimal(text.replace(',', '.'));
    }
}
