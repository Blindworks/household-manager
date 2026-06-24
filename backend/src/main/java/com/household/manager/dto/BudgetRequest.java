package com.household.manager.dto;

import jakarta.validation.constraints.DecimalMin;
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
    @DecimalMin(value = "0.01", message = "Budget muss positiv sein")
    private BigDecimal amount;
}
