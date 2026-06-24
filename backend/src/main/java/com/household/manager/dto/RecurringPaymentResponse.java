package com.household.manager.dto;

import com.household.manager.model.entity.RecurrenceInterval;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class RecurringPaymentResponse {
    private final Long id;
    private final Long accountId;
    private final String counterpartyPattern;
    private final Long categoryId;
    private final BigDecimal expectedAmount;
    private final RecurrenceInterval interval;
    private final LocalDate nextDueDate;
    private final boolean confirmed;
}
