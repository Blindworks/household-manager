package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class BudgetResponse {
    private final Long id;
    private final Long categoryId;
    private final String period;
    private final BigDecimal amount;
}
