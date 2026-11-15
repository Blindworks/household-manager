package com.household.manager.grocery.matching;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Einzige Definition der Wort-Normalisierung und der kommagetrennten Wortlisten. */
public final class ProductWords {

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** Komma trennt nur, wenn nicht beidseitig eine Ziffer steht ("0,33" bleibt ein Eintrag). */
    private static final Pattern LIST_SEPARATOR = Pattern.compile("(?<!\\d),|,(?!\\d)");

    private ProductWords() {
    }

    /** Kommagetrennte Liste in nicht leere, getrimmte Einträge. */
    public static List<String> split(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        return Arrays.stream(LIST_SEPARATOR.split(commaSeparated))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Kleingeschrieben, Nicht-Buchstaben/-Ziffern als Trenner; Umlaute bleiben. */
    public static List<String> tokens(String text) {
        if (text == null) {
            return List.of();
        }
        String normalized = NON_ALNUM.matcher(Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace("ß", "ss")).replaceAll(" ").trim();
        return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split(" "));
    }

    /** True, wenn die Token der Phrase als zusammenhängende Folge in den Text-Token vorkommen. */
    public static boolean containsPhrase(List<String> textTokens, String phrase) {
        List<String> needle = tokens(phrase);
        return !needle.isEmpty() && Collections.indexOfSubList(textTokens, needle) >= 0;
    }
}
