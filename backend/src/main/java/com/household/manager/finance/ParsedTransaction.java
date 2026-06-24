package com.household.manager.finance;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A single transaction extracted from a camt.053 entry. Amount is signed (negative = debit). */
@Data
@Builder
public class ParsedTransaction {
    private final LocalDate bookingDate;
    private final LocalDate valueDate;
    private final BigDecimal amount;
    private final String currency;
    private final String counterpartyName;
    private final String counterpartyIban;
    private final String purpose;
    private final String endToEndId;
    private final String accountServicerReference;
    private final String bankTxCode;
}
