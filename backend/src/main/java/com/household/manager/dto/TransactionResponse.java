package com.household.manager.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class TransactionResponse {
    private final Long id;
    private final Long accountId;
    private final LocalDate bookingDate;
    private final LocalDate valueDate;
    private final BigDecimal amount;
    private final String currency;
    private final String counterpartyName;
    private final String counterpartyIban;
    private final String purpose;
    private final Long categoryId;
    private final boolean manuallyCategorized;
}
