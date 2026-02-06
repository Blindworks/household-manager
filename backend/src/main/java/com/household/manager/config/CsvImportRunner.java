package com.household.manager.config;

import com.household.manager.importer.MeterReadingCsvImporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Runs CSV import on startup when the household.import.csv property is set.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CsvImportRunner implements CommandLineRunner {

    @Value("${household.import.csv:}")
    private String csvPath;

    private final MeterReadingCsvImporter importer;

    @Override
    public void run(String... args) throws Exception {
        if (csvPath == null || csvPath.isBlank()) {
            return;
        }

        log.info("Starting CSV import from {}", csvPath);
        int created = importer.importFromPath(Path.of(csvPath));
        log.info("CSV import completed. Created {} meter readings.", created);
    }
}
