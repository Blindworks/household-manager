package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class CategorySpendItem {
    private final Long categoryId;   // null = uncategorized
    private final String categoryName;
    private final String color;
    private final BigDecimal amount; // positive magnitude of expenses
}
