package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class RuleReevaluationRow {
    private final Long transactionId;
    private final LocalDate bookingDate;
    private final String counterpartyName;
    private final String purpose;
    private final BigDecimal amount;
    private final Long oldCategoryId;
    private final Long newCategoryId;
}
