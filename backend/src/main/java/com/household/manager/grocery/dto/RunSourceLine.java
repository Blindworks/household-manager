package com.household.manager.grocery.dto;

/** Ergebnis einer Quelle in einem Lauf; {@code offers} fehlt, wenn die Quelle ausfiel. */
public record RunSourceLine(String source, boolean ok, Integer offers, String reason) {
}
