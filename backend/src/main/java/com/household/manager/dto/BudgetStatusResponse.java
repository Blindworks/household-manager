package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BudgetStatusResponse {
    private final BudgetStatusItem overall;     // null if no overall budget set
    private final List<BudgetStatusItem> categories;
}
