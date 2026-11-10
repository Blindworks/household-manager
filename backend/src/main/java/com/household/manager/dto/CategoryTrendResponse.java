package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ausgaben je Kategorie über mehrere Monate. {@code amounts} ist je Kategorie parallel zu
 * {@code months} (ein Wert je Monat, 0 ohne Ausgaben); Kategorien nach Gesamtsumme absteigend.
 */
@Data
@Builder
public class CategoryTrendResponse {
    private final List<String> months; // yyyy-MM, aufsteigend
    private final List<Row> categories;

    @Data
    @Builder
    public static class Row {
        private final Long categoryId;   // null = unkategorisiert
        private final String categoryName;
        private final String color;
        private final List<BigDecimal> amounts;
        private final BigDecimal total;
    }
}
