package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CamtStatementParserTest {

    private final CamtStatementParser parser = new CamtStatementParser();

    private ParsedStatement parseSample() {
        InputStream in = getClass().getResourceAsStream("/camt/sample-camt053.xml");
        assertNotNull(in, "sample file must be on the test classpath");
        return parser.parse(in);
    }

    @Test
    void parsesAccountIbanAndCurrency() {
        ParsedStatement stmt = parseSample();
        assertEquals("DE00111122223333444455", stmt.getAccountIban());
        assertEquals("EUR", stmt.getCurrency());
    }

    @Test
    void debitEntryBecomesNegativeAmountWithCreditorAsCounterparty() {
        List<ParsedTransaction> tx = parseSample().getTransactions();
        ParsedTransaction netflix = tx.get(0);
        assertEquals(0, new BigDecimal("-29.99").compareTo(netflix.getAmount()));
        assertEquals("NETFLIX INTERNATIONAL", netflix.getCounterpartyName());
        assertEquals("NL00NETFLIX0000001", netflix.getCounterpartyIban());
        assertEquals(LocalDate.of(2026, 6, 1), netflix.getBookingDate());
        assertEquals(LocalDate.of(2026, 6, 2), netflix.getValueDate());
        assertEquals("Netflix Abo Juni", netflix.getPurpose());
        assertEquals("E2E-NETFLIX-06", netflix.getEndToEndId());
        assertEquals("REF-0001", netflix.getAccountServicerReference());
        assertEquals("PMNT", netflix.getBankTxCode());
    }

    @Test
    void creditEntryBecomesPositiveAmountWithDebtorAsCounterparty() {
        ParsedTransaction salary = parseSample().getTransactions().get(1);
        assertEquals(0, new BigDecimal("2500.00").compareTo(salary.getAmount()));
        assertEquals("ARBEITGEBER GMBH", salary.getCounterpartyName());
    }

    @Test
    void invalidXmlThrowsCamtParseException() {
        String notCamt = "<foo>bar</foo>";
        assertThrows(CamtParseException.class,
                () -> parser.parse(new java.io.ByteArrayInputStream(notCamt.getBytes())));
    }
}
