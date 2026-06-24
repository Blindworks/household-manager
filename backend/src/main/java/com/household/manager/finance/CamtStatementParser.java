package com.household.manager.finance;

import com.household.manager.finance.camt.CamtDocument;
import com.household.manager.finance.camt.CamtModel;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses camt.053.001.08 statements into plain {@link ParsedStatement} DTOs.
 * Pure (no DB); accepts a stream so it is trivially unit-testable.
 */
@Component
@Slf4j
public class CamtStatementParser {

    // JAXBContext is thread-safe and expensive to build — cache it once.
    private static final JAXBContext JAXB_CONTEXT = createContext();

    private static JAXBContext createContext() {
        try {
            return JAXBContext.newInstance(CamtDocument.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to initialise JAXB context", e);
        }
    }

    public ParsedStatement parse(InputStream xml) {
        CamtDocument document = unmarshal(xml);

        if (document.getBkToCstmrStmt() == null
                || document.getBkToCstmrStmt().getStatements().isEmpty()) {
            throw new CamtParseException("No statement (Stmt) found — not a camt.053 document");
        }

        // We support single-statement files (the common bank export). Merge entries if multiple.
        String accountIban = null;
        String currency = null;
        List<ParsedTransaction> transactions = new ArrayList<>();

        for (CamtModel.Statement stmt : document.getBkToCstmrStmt().getStatements()) {
            if (stmt.getAccount() != null) {
                if (accountIban == null) {
                    accountIban = stmt.getAccount().getIban();
                }
                if (currency == null) {
                    currency = stmt.getAccount().getCurrency();
                }
            }
            for (CamtModel.Entry entry : stmt.getEntries()) {
                ParsedTransaction tx = toTransaction(entry, currency);
                if (tx != null) {
                    transactions.add(tx);
                }
            }
        }

        return ParsedStatement.builder()
                .accountIban(accountIban)
                .currency(currency)
                .transactions(transactions)
                .build();
    }

    private CamtDocument unmarshal(InputStream xml) {
        try {
            Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
            Object result = unmarshaller.unmarshal(xml);
            if (!(result instanceof CamtDocument doc)) {
                throw new CamtParseException("Root element is not a camt.053 Document");
            }
            return doc;
        } catch (JAXBException e) {
            throw new CamtParseException("Failed to parse CAMT XML", e);
        }
    }

    /** Maps one entry to a ParsedTransaction; returns null if essential data is missing. */
    private ParsedTransaction toTransaction(CamtModel.Entry entry, String stmtCurrency) {
        if (entry.getAmount() == null || entry.getAmount().getValue() == null) {
            log.warn("Skipping CAMT entry without amount");
            return null;
        }
        boolean debit = entry.isDebit();
        BigDecimal magnitude = entry.getAmount().getValue().abs();
        BigDecimal signed = debit ? magnitude.negate() : magnitude;

        String currency = entry.getAmount().getCurrency() != null
                ? entry.getAmount().getCurrency() : stmtCurrency;

        LocalDate bookingDate = parseDate(entry.getBookingDate() != null
                ? entry.getBookingDate().resolveDate() : null);
        LocalDate valueDate = parseDate(entry.getValueDate() != null
                ? entry.getValueDate().resolveDate() : null);

        if (bookingDate == null) {
            log.warn("Skipping CAMT entry without booking date");
            return null;
        }

        String counterpartyName = null;
        String counterpartyIban = null;
        String purpose = null;
        String endToEndId = null;

        CamtModel.EntryDetails details = entry.getEntryDetails();
        if (details != null && !details.getTransactionDetails().isEmpty()) {
            CamtModel.TransactionDetails td = details.getTransactionDetails().get(0);
            if (td.getRelatedParties() != null) {
                counterpartyName = td.getRelatedParties().counterpartyName(debit);
                counterpartyIban = td.getRelatedParties().counterpartyIban(debit);
            }
            purpose = td.getRemittanceText();
            endToEndId = td.getEndToEndId();
        }

        String bankTxCode = entry.getBankTransactionCode() != null
                ? entry.getBankTransactionCode().resolveCode() : null;

        return ParsedTransaction.builder()
                .bookingDate(bookingDate)
                .valueDate(valueDate)
                .amount(signed)
                .currency(currency)
                .counterpartyName(counterpartyName)
                .counterpartyIban(counterpartyIban)
                .purpose(purpose)
                .endToEndId(endToEndId)
                .accountServicerReference(entry.getAccountServicerReference())
                .bankTxCode(bankTxCode)
                .build();
    }

    private LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            throw new CamtParseException("Unparseable date in CAMT entry: " + iso, e);
        }
    }
}
