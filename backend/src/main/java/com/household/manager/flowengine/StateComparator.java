package com.household.manager.flowengine;

import java.math.BigDecimal;

/**
 * Vergleicht Entity-Zustände (Strings) mit einem Operator. Numerische Operatoren
 * parsen beide Seiten als Zahl; nicht parsebare Zustände (unavailable, unknown,
 * "on", null) matchen numerisch nie. ==/!= vergleichen numerisch, wenn beide
 * Seiten Zahlen sind (5.0 == 5), sonst als String.
 */
public final class StateComparator {

    private StateComparator() {
    }

    public static boolean matches(String state, String operator, String value) {
        if (state == null || operator == null || value == null) {
            return false;
        }
        BigDecimal left = parse(state);
        BigDecimal right = parse(value);
        boolean numeric = left != null && right != null;

        return switch (operator) {
            case "==" -> numeric ? left.compareTo(right) == 0 : state.equals(value);
            case "!=" -> numeric ? left.compareTo(right) != 0 : !state.equals(value);
            case "<" -> numeric && left.compareTo(right) < 0;
            case "<=" -> numeric && left.compareTo(right) <= 0;
            case ">" -> numeric && left.compareTo(right) > 0;
            case ">=" -> numeric && left.compareTo(right) >= 0;
            default -> false;
        };
    }

    private static BigDecimal parse(String text) {
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
