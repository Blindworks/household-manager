package com.household.manager.finance;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CamtCsvStatementParserTest {

    private final CamtCsvStatementParser parser = new CamtCsvStatementParser();

    private ParsedStatement parseSample() {
        InputStream in = getClass().getResourceAsStream("/camt/sample-camt-csv.csv");
        assertNotNull(in, "sample-camt-csv.csv must be on the test classpath");
        return parser.parse(in);
    }

    @Test
    void parsesThreeTransactionsSkippingInvalidRows() {
        List<ParsedTransaction> transactions = parseSample().getTransactions();
        // fixture has 5 data rows: 3 valid, 1 with empty Betrag (silent skip), 1 with garbage Betrag (resilient skip)
        assertEquals(3, transactions.size(), "rows with empty or malformed Betrag must both be skipped");
    }

    @Test
    void malformedBetragRowIsSkippedAndOtherRowsStillParse() {
        // Confirms row-level resilience: a garbage non-blank Betrag does not abort the whole file.
        List<ParsedTransaction> transactions = parseSample().getTransactions();
        assertEquals(3, transactions.size(), "bad-Betrag row must be skipped, leaving 3 valid rows");
        // Verify the bad row's counterparty is not present
        boolean badRowPresent = transactions.stream()
                .anyMatch(tx -> "BAD PAYER GMBH".equals(tx.getCounterpartyName()));
        assertFalse(badRowPresent, "the malformed row must not appear in the parsed results");
        // Verify the valid rows are still present
        assertTrue(transactions.stream().anyMatch(tx -> "ARBEITGEBER GMBH".equals(tx.getCounterpartyName())),
                "credit row must still be parsed after the bad row is skipped");
    }

    @Test
    void debitRowHasCorrectFields() {
        ParsedTransaction tx = parseSample().getTransactions().get(0);

        assertEquals(0, new BigDecimal("-22.93").compareTo(tx.getAmount()),
                "amount should be -22.93");
        assertEquals("LIDL SAGT DANKE/Alte Frankfurter Str. 15/Bad Vilbel/DE",
                tx.getCounterpartyName());
        assertEquals("DE61300500000008000119", tx.getCounterpartyIban());
        assertEquals("Einkauf Lebensmittel", tx.getPurpose());
        assertEquals("E2E-LIDL-001", tx.getEndToEndId());
        assertEquals("DIGITALE KARTE (APPLE PAY)", tx.getBankTxCode());
        assertEquals("EUR", tx.getCurrency());
        assertEquals(LocalDate.of(2026, 6, 24), tx.getBookingDate());
        assertEquals(LocalDate.of(2026, 6, 24), tx.getValueDate());
    }

    @Test
    void thousandsSeparatorAmountParsedCorrectly() {
        ParsedTransaction tx = parseSample().getTransactions().get(1);
        assertEquals(0, new BigDecimal("-1234.56").compareTo(tx.getAmount()),
                "German thousands-dot must be stripped before parsing");
    }

    @Test
    void creditRowHasPositiveAmount() {
        ParsedTransaction tx = parseSample().getTransactions().get(2);
        assertEquals(0, new BigDecimal("2500.00").compareTo(tx.getAmount()),
                "credit amount should be positive");
        assertEquals("ARBEITGEBER GMBH", tx.getCounterpartyName());
    }

    @Test
    void statementHasAccountIbanAndCurrencyFromFirstRow() {
        ParsedStatement stmt = parseSample();
        assertEquals("DE00111122223333444455", stmt.getAccountIban());
        assertEquals("EUR", stmt.getCurrency());
    }

    @Test
    void invalidContentThrowsCamtParseException() {
        byte[] html = "<html><body>not a csv</body></html>".getBytes();
        assertThrows(CamtParseException.class,
                () -> parser.parse(new ByteArrayInputStream(html)));
    }

    @Test
    void pendingRowsAreSkippedBecauseTheyAreBookedAgainLater() {
        // Sparkasse exportiert vorgemerkte Kartenumsaetze ("MO ...") zusaetzlich; nach der
        // Buchung erscheinen sie ein zweites Mal mit anderem Zweck - importiert waeren sie doppelt.
        String csv = "\"Auftragskonto\";\"Buchungstag\";\"Valutadatum\";\"Buchungstext\";\"Verwendungszweck\";"
                + "\"Beguenstigter/Zahlungspflichtiger\";\"Kontonummer/IBAN\";\"Betrag\";\"Waehrung\";\"Info\"\n"
                + "\"DE00\";\"09.11.26\";\"\";\"SONSTIGER EINZUG\";\"MO 54385708 071126142513C13 \";"
                + "\"\";\"0000000000\";\"-15,90\";\"EUR\";\"Umsatz vorgemerkt\"\n"
                + "\"DE00\";\"09.11.26\";\"09.11.26\";\"DIGITALE KARTE (APPLE PAY)\";\"2026-11-07T14:25 Debitk.13 2027-12\";"
                + "\"CINEPLEX MARBURG//MARBURG/DE\";\"DE62664926000009104208\";\"-15,90\";\"EUR\";\"Umsatz gebucht\"\n";

        List<ParsedTransaction> transactions = parser.parse(
                new ByteArrayInputStream(csv.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).getTransactions();

        assertEquals(1, transactions.size(), "vorgemerkte Zeile darf nicht importiert werden");
        assertEquals("CINEPLEX MARBURG//MARBURG/DE", transactions.get(0).getCounterpartyName());
    }
}
