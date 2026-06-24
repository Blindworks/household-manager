package com.household.manager.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** categoryId null = overall budget. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BudgetRequest {
    private Long categoryId;
    @NotNull
    private BigDecimal amount;
}
