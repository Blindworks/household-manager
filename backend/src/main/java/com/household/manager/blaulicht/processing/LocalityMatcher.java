package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.BlaulichtProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ortsfilter — einzige Definition, welcher Fall Bad Vilbel betrifft.
 *
 * <p>Maßgeblich ist die Kopfzeile des Falls („Bad Vilbel: …“). Bei einem Fall im Hauptort wird im Text
 * nach dem Ortsteil gesucht (ganzes Wort: „Dortelweil“ trifft, „Dortelweiler Straße“ nicht). Ohne Kopfzeile
 * wird Überschrift und Text nach dem Hauptort durchsucht; ein Ortsteil allein zählt dort nicht (ein
 * „Gronau“ ohne Bad Vilbel kann woanders liegen). Nachbarorte bekommen die niedrige Relevanz.
 * Kein Treffer ⇒ leer, der Fall wird verworfen.
 */
public final class LocalityMatcher {

    private final String main;
    private final List<String> districts;
    private final List<String> neighbors;
    private final int mainRelevance;
    private final int neighborRelevance;

    public LocalityMatcher(BlaulichtProperties.Localities config) {
        this.main = config.getMain();
        this.districts = List.copyOf(config.getDistricts());
        this.neighbors = List.copyOf(config.getNeighbors());
        this.mainRelevance = config.getMainRelevance();
        this.neighborRelevance = config.getNeighborRelevance();
    }

    public Optional<LocalityMatch> match(String placeHeader, String headline, String body) {
        String text = (headline == null ? "" : headline) + "\n" + (body == null ? "" : body);
        if (placeHeader != null && !placeHeader.isBlank()) {
            return matchHeader(placeHeader, text);
        }
        if (containsWord(text, main)) {
            return Optional.of(mainMatch(text));
        }
        return firstNeighbor(text);
    }

    /** Ob der Text einen konfigurierten Ort nennt (Vorauswahl, welche Detailseiten gelesen werden). */
    public boolean mentionsAny(String text) {
        return containsWord(text, main)
                || districts.stream().anyMatch(d -> containsWord(text, d))
                || neighbors.stream().anyMatch(n -> containsWord(text, n));
    }

    private Optional<LocalityMatch> matchHeader(String header, String text) {
        List<String> places = splitPlaces(header);
        for (String place : places) {
            if (containsWord(place, main)) {
                Optional<String> district = districts.stream().filter(d -> containsWord(place, d)).findFirst();
                return Optional.of(district
                        .map(d -> new LocalityMatch(d, mainRelevance, false))
                        .orElseGet(() -> mainMatch(text)));
            }
            Optional<String> district = districts.stream().filter(d -> containsWord(place, d)).findFirst();
            if (district.isPresent()) {
                return Optional.of(new LocalityMatch(district.get(), mainRelevance, false));
            }
        }
        return firstNeighbor(header);
    }

    private LocalityMatch mainMatch(String text) {
        String locality = districts.stream().filter(d -> containsWord(text, d)).findFirst().orElse(main);
        return new LocalityMatch(locality, mainRelevance, false);
    }

    private Optional<LocalityMatch> firstNeighbor(String text) {
        return neighbors.stream()
                .filter(n -> containsWord(text, n))
                .findFirst()
                .map(n -> new LocalityMatch(displayName(n), neighborRelevance, true));
    }

    /** „Frankfurt-Berkersheim“ bleibt so; nur der Name, wie er konfiguriert ist. */
    private static String displayName(String neighbor) {
        return neighbor;
    }

    private static List<String> splitPlaces(String header) {
        List<String> places = new ArrayList<>();
        for (String part : header.split("\\s*(?:/|,|\\bund\\b)\\s*")) {
            if (!part.isBlank()) {
                places.add(part.trim());
            }
        }
        return places;
    }

    static boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return false;
        }
        String flexible = Arrays.stream(word.trim().split("[\\s\\-]+"))
                .map(Pattern::quote)
                .collect(Collectors.joining("[\\s\\-]+"));
        return Pattern.compile("(?<![\\p{L}])" + flexible + "(?![\\p{L}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }
}
