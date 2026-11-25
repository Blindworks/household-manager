package com.household.manager.blaulicht;

import com.household.manager.blaulicht.source.PresseportalDetailParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Echte Antworten der Quellen, aufgezeichnet am 2026-11-25 (siehe {@code src/test/resources/blaulicht}). */
public final class BlaulichtFixtures {

    private BlaulichtFixtures() {
    }

    public static String read(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/blaulicht", name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static PresseportalDetailParser.Detail detail(String id) {
        return PresseportalDetailParser.parse(read("presseportal-detail-" + id + ".html")).orElseThrow();
    }
}
