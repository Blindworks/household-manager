package com.household.manager.dto;

import com.household.manager.finance.BudgetStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BudgetStatusItem {
    private final Long categoryId;     // null = overall
    private final String categoryName; // "Gesamt" for overall
    private final BigDecimal limit;
    private final BigDecimal spent;
    private final int percent;
    private final BudgetStatus status;
}
