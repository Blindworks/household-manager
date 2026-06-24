package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class OverviewResponse {
    private final String month;               // yyyy-MM
    private final BigDecimal totalExpenses;   // positive magnitude, TRANSFER excluded
    private final BigDecimal totalIncome;     // TRANSFER excluded
    private final BigDecimal balance;         // income - expenses (consumption balance)
    private final BigDecimal totalInvestments; // absolute outflow via TRANSFER categories
    private final Integer savingsRate;         // totalInvestments / totalIncome * 100, null if no income
    private final BudgetStatusResponse budget;
    private final List<CategorySpendItem> categories;
}
