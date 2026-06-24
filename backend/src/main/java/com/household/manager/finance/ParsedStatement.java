package com.household.manager.finance;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Result of parsing one camt.053 statement: account IBAN/currency plus its transactions. */
@Data
@Builder
public class ParsedStatement {
    private final String accountIban;
    private final String currency;
    private final List<ParsedTransaction> transactions;
}
