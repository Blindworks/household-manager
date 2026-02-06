package com.household.manager.importer;

import com.household.manager.model.entity.MeterReading;
import com.household.manager.model.entity.MeterType;
import com.household.manager.repository.MeterReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports meter readings from the "Ressourcenverbrauch - Gesamtübersicht Wochen" CSV format.
 * Focuses on meter readings only (electricity, gas, water).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MeterReadingCsvImporter {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final int COL_DATE = 0;
    private static final int COL_WEEK = 1;
    private static final int COL_ELECTRICITY_READING = 2;
    private static final int COL_ELECTRICITY_NOTES = 5;
    private static final int COL_GAS_READING = 7;
    private static final int COL_WATER_READING = 12;

    private final MeterReadingRepository meterReadingRepository;

    /**
     * Imports meter readings from the given CSV file path.
     *
     * @param csvPath path to the CSV file
     * @return number of created meter readings
     */
    public int importFromPath(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            log.error("CSV file not found: {}", csvPath);
            return 0;
        }

        try (var reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            return importFromReader(reader);
        }
    }

    /**
     * Imports meter readings from a reader (e.g., multipart upload).
     *
     * @param reader CSV reader
     * @return number of created meter readings
     */
    public int importFromReader(Reader reader) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .setQuote('"')
                .build();

        int createdCount = 0;

        try (CSVParser parser = new CSVParser(reader, format)) {
            for (CSVRecord record : parser) {
                LocalDate readingDate = parseDate(record, COL_DATE);
                if (readingDate == null) {
                    continue;
                }

                LocalDateTime readingDateTime = readingDate.atStartOfDay();
                Integer readingWeek = parseInteger(getValue(record, COL_WEEK));

                String electricityNote = getValue(record, COL_ELECTRICITY_NOTES);
                String extraNote = findExtraNote(record, 13);
                String combinedNote = combineNotes(electricityNote, extraNote);

                createdCount += createReadingIfPresent(MeterType.ELECTRICITY,
                        getValue(record, COL_ELECTRICITY_READING),
                        readingDateTime,
                        readingWeek,
                        combinedNote);

                createdCount += createReadingIfPresent(MeterType.GAS,
                        getValue(record, COL_GAS_READING),
                        readingDateTime,
                        readingWeek,
                        extraNote);

                createdCount += createReadingIfPresent(MeterType.WATER,
                        getValue(record, COL_WATER_READING),
                        readingDateTime,
                        readingWeek,
                        extraNote);
            }
        }

        log.info("CSV import finished. Created {} meter readings.", createdCount);
        return createdCount;
    }

    private int createReadingIfPresent(MeterType meterType, String rawValue,
                                       LocalDateTime readingDate, Integer readingWeek, String notes) {
        BigDecimal readingValue = parseDecimal(rawValue);
        if (readingValue == null) {
            return 0;
        }

        if (meterReadingRepository.existsByMeterTypeAndReadingDate(meterType, readingDate)) {
            return 0;
        }

        MeterReading meterReading = MeterReading.builder()
                .meterType(meterType)
                .readingValue(readingValue)
                .readingWeek(readingWeek)
                .readingDate(readingDate)
                .notes(notes)
                .build();

        meterReadingRepository.save(meterReading);
        return 1;
    }

    private LocalDate parseDate(CSVRecord record, int index) {
        String raw = getValue(record, index);
        if (raw.isEmpty() || raw.equalsIgnoreCase("Datum")) {
            return null;
        }

        try {
            return LocalDate.parse(raw, DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        value = value.replace("€", "")
                .replace("EUR", "")
                .replace("Â", "")
                .replace(" ", "");

        boolean hasComma = value.contains(",");
        boolean hasDot = value.contains(".");

        if (hasComma && hasDot) {
            value = value.replace(".", "");
            value = value.replace(",", ".");
        } else if (hasComma) {
            value = value.replace(",", ".");
        }

        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseInteger(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String getValue(CSVRecord record, int index) {
        if (index < 0 || index >= record.size()) {
            return "";
        }
        String value = record.get(index);
        return value != null ? value.trim() : "";
    }

    private String findExtraNote(CSVRecord record, int startIndex) {
        if (record.size() <= startIndex) {
            return "";
        }

        List<String> notes = new ArrayList<>();
        for (int i = startIndex; i < record.size(); i++) {
            String value = getValue(record, i);
            if (!value.isEmpty() && containsLetters(value)) {
                notes.add(value);
            }
        }
        return String.join(" | ", notes);
    }

    private boolean containsLetters(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String combineNotes(String first, String second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return first + " | " + second;
    }
}
