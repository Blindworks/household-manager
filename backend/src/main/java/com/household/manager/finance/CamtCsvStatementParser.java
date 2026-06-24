package com.household.manager.finance;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses Sparkasse "CSV-CAMT" export files (ISO-8859-1, semicolon-delimited) into
 * {@link ParsedStatement} DTOs.
 *
 * <p>The format is not a real CAMT document but a proprietary CSV used by German savings banks.
 * Encoding is ISO-8859-1; the decimal separator is a comma and thousands grouping uses a dot.
 * Both 2-digit ({@code dd.MM.yy}) and 4-digit ({@code dd.MM.yyyy}) year formats are accepted.
 */
@Component
@Slf4j
public class CamtCsvStatementParser implements StatementParser {

    private static final DateTimeFormatter DATE_FORMAT_4Y = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_FORMAT_2Y = DateTimeFormatter.ofPattern("dd.MM.yy");

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(';')
            .setQuote('"')
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(true)
            .build();

    private static final Set<String> REQUIRED_HEADERS = Set.of(
            "Buchungstag", "Betrag", "Beguenstigter/Zahlungspflichtiger", "Kontonummer/IBAN"
    );

    @Override
    public ParsedStatement parse(InputStream in) {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.ISO_8859_1);
             CSVParser csvParser = CSV_FORMAT.parse(reader)) {
            validateHeaders(csvParser);
            return buildStatement(csvParser);
        } catch (CamtParseException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            throw new CamtParseException("Die Datei ist keine gueltige CSV-CAMT-Datei", e);
        }
    }

    private void validateHeaders(CSVParser csvParser) {
        if (!csvParser.getHeaderMap().keySet().containsAll(REQUIRED_HEADERS)) {
            throw new CamtParseException("Die Datei ist keine gueltige CSV-CAMT-Datei");
        }
    }

    private ParsedStatement buildStatement(CSVParser csvParser) throws IOException {
        String accountIban = null;
        String currency = null;
        List<ParsedTransaction> transactions = new ArrayList<>();

        for (CSVRecord record : csvParser) {
            try {
                ParsedTransaction tx = toTransaction(record);
                if (tx == null) {
                    continue;
                }
                if (accountIban == null) {
                    accountIban = value(record, "Auftragskonto");
                }
                if (currency == null) {
                    currency = value(record, "Waehrung");
                }
                transactions.add(tx);
            } catch (Exception e) {
                log.warn("Skipping CSV row {} due to parse error: {}", record.getRecordNumber(), e.getMessage());
            }
        }

        if (accountIban == null && transactions.isEmpty()) {
            throw new CamtParseException("Die Datei ist keine gueltige CSV-CAMT-Datei");
        }

        return ParsedStatement.builder()
                .accountIban(accountIban)
                .currency(currency)
                .transactions(transactions)
                .build();
    }

    /**
     * Maps one CSV record to a {@link ParsedTransaction}.
     * Returns {@code null} if the row should be silently skipped (blank Betrag or Buchungstag).
     * Throws on a non-blank but malformed value so the caller can log and skip the row.
     */
    private ParsedTransaction toTransaction(CSVRecord record) {
        String betrag = value(record, "Betrag");
        if (betrag == null) {
            log.warn("Skipping CSV row {} — Betrag is empty", record.getRecordNumber());
            return null;
        }

        LocalDate bookingDate = parseGermanDate(value(record, "Buchungstag"));
        if (bookingDate == null) {
            log.warn("Skipping CSV row {} — Buchungstag is empty", record.getRecordNumber());
            return null;
        }

        LocalDate valueDate = parseGermanDateLenient(value(record, "Valutadatum"));

        return ParsedTransaction.builder()
                .bookingDate(bookingDate)
                .valueDate(valueDate)
                .amount(parseGermanAmount(betrag))
                .currency(value(record, "Waehrung"))
                .counterpartyName(value(record, "Beguenstigter/Zahlungspflichtiger"))
                .counterpartyIban(value(record, "Kontonummer/IBAN"))
                .purpose(value(record, "Verwendungszweck"))
                .endToEndId(value(record, "Kundenreferenz (End-to-End)"))
                .bankTxCode(value(record, "Buchungstext"))
                .accountServicerReference(null)
                .build();
    }

    /** Returns the trimmed cell value, or {@code null} if the cell is blank or absent. */
    private String value(CSVRecord record, String header) {
        if (!record.isMapped(header)) {
            return null;
        }
        String val = record.get(header).trim();
        return val.isEmpty() ? null : val;
    }

    /**
     * Parses a date in {@code dd.MM.yyyy} or {@code dd.MM.yy} format.
     * Tries 4-digit year first, falls back to 2-digit.
     *
     * @return {@code null} if {@code s} is blank
     * @throws CamtParseException if the value is non-blank but cannot be parsed in either format
     */
    private LocalDate parseGermanDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String trimmed = s.trim();
        try {
            return LocalDate.parse(trimmed, DATE_FORMAT_4Y);
        } catch (DateTimeParseException ignored) {
            // fall through to 2-digit year
        }
        try {
            return LocalDate.parse(trimmed, DATE_FORMAT_2Y);
        } catch (DateTimeParseException e) {
            throw new CamtParseException("Ungültiges Datum in CSV-CAMT-Datei: " + s, e);
        }
    }

    /**
     * Like {@link #parseGermanDate(String)} but returns {@code null} on parse failure
     * instead of throwing, so a bad Valutadatum never discards an otherwise-valid row.
     */
    private LocalDate parseGermanDateLenient(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return parseGermanDate(s);
        } catch (CamtParseException e) {
            log.warn("Ignoring unparseable Valutadatum '{}' — will be null", s);
            return null;
        }
    }

    /**
     * Parses a German-formatted decimal amount (e.g. {@code -1.234,56} or {@code +2500,00}).
     * Removes thousand-grouping dots and replaces the decimal comma with a dot.
     *
     * @return {@code null} if {@code s} is blank
     * @throws CamtParseException if the value is non-blank but cannot be parsed
     */
    private BigDecimal parseGermanAmount(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            String normalized = s.trim().replace(".", "").replace(",", ".");
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new CamtParseException("Ungültiger Betrag in CSV-CAMT-Datei: " + s, e);
        }
    }
}
