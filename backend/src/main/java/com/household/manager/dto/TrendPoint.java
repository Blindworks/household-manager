package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class TrendPoint {
    private final String month;     // yyyy-MM
    private final BigDecimal expenses; // positive magnitude
    private final BigDecimal income;
}
