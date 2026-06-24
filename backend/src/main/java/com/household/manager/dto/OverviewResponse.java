package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OverviewResponse {
    private final String month;            // yyyy-MM
    private final BigDecimal totalExpenses; // positive magnitude
    private final BigDecimal totalIncome;
    private final BigDecimal balance;       // income - expenses
    private final BudgetStatusResponse budget;
    private final List<CategorySpendItem> categories;
}
