package com.household.manager.blaulicht.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zerlegt eine Polizeimeldung in einzelne Fälle — einzige Definition dieser Regel.
 *
 * <p>Die Pressestelle Mittelhessen bündelt mehrere Fälle in einer Meldung („WETTERAUKREIS: + Einbruch … +
 * Werkzeuge gestohlen +“). Im Volltext beginnt jeder Fall mit einer eigenen Kopfzeile
 * {@code Ort: Überschrift} (auch {@code Ort / Ort: …}), danach folgen dessen Absätze; das „+“ steht nur im
 * Titel. Eine Meldung ohne Kopfzeile ist ein einziger Fall mit dem bereinigten Titel als Überschrift.
 * Text vor der ersten Kopfzeile (Ortsmarke „Giessen (ots)“, „Pressemeldung vom …“) und der Abspann
 * („Rückfragen bitte an“, Unterschrift der Pressestelle) gehören zu keinem Fall.
 */
public final class BulletinSplitter {

    private static final int MAX_HEADER_LENGTH = 160;
    private static final int MAX_PLACE_LENGTH = 70;
    private static final Pattern DATELINE = Pattern.compile("^\\s*[\\p{L} .\\-]{2,40}\\(ots\\)\\s*[-–]?\\s*");
    private static final Pattern PRESS_DATE = Pattern.compile("^\\s*Pressemeldung vom \\d{1,2}\\.\\d{1,2}\\.\\d{2,4}\\s*",
            Pattern.CASE_INSENSITIVE);
    /** Ortsangabe: Wörter mit Großbuchstaben (danach auch Straßennummern wie „A 5“), verbunden durch Leerzeichen, Bindestrich, „/“, Komma oder „und“. */
    private static final Pattern PLACE = Pattern.compile(
            "^\\p{Lu}[\\p{L}.\\-]*(?:(?:\\s*[/,]\\s*|\\s+(?:und|u\\.|-|–)\\s+|[\\s\\-])(?:\\p{Lu}[\\p{L}\\d.\\-]*|\\d{1,4}))*$");
    private static final List<String> NOT_A_PLACE = List.of("hinweise", "zeugen", "rückfragen", "telefon", "tel",
            "kontakt", "pressemeldung", "update", "nachtrag", "ergänzung", "achtung", "wichtig", "hinweis",
            "mittelhessen", "wetteraukreis", "pol-we", "pol-gi", "zeugenaufruf", "foto", "bild");
    private static final Pattern TITLE_PREFIX = Pattern.compile(
            "^(?:\\s*(?:Mittelhessen|MITTELHESSEN|WETTERAUKREIS|Wetteraukreis|POL-WE|POL-GI|POL-[A-Z]{2,4})\\s*[:\\-–]\\s*)+");

    private BulletinSplitter() {
    }

    public static List<BulletinCase> split(String title, List<String> paragraphs) {
        List<String> body = withoutFooter(paragraphs);
        List<BulletinCase> cases = new ArrayList<>();
        String place = null;
        String headline = null;
        List<String> current = new ArrayList<>();
        for (String raw : body) {
            String paragraph = stripLead(raw);
            if (paragraph.isBlank()) {
                continue;
            }
            Matcher header = headerOf(paragraph);
            if (header != null) {
                if (headline != null) {
                    cases.add(new BulletinCase(place, headline, String.join("\n\n", current)));
                }
                place = header.group(1).trim();
                headline = header.group(2).trim();
                current = new ArrayList<>();
            } else {
                current.add(paragraph);
            }
        }
        if (headline != null) {
            cases.add(new BulletinCase(place, headline, String.join("\n\n", current)));
            return cases;
        }
        return List.of(new BulletinCase(null, cleanTitle(title), String.join("\n\n", current)));
    }

    /** Titel ohne Dienststellen-Präfixe und „+“-Klammern. */
    public static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String cleaned = TITLE_PREFIX.matcher(title.trim()).replaceFirst("");
        cleaned = cleaned.replaceAll("^\\s*\\+\\s*", "").replaceAll("\\s*\\+\\s*$", "");
        return cleaned.replaceAll("\\s*\\+\\s*", " · ").trim();
    }

    private static final Pattern HEADER = Pattern.compile("^([^:]{2," + MAX_PLACE_LENGTH + "}):\\s+(\\S.*)$");

    private static Matcher headerOf(String paragraph) {
        if (paragraph.length() > MAX_HEADER_LENGTH || paragraph.contains("\n")) {
            return null;
        }
        Matcher matcher = HEADER.matcher(paragraph);
        if (!matcher.matches()) {
            return null;
        }
        String place = matcher.group(1).trim();
        String rest = matcher.group(2).trim();
        if (!PLACE.matcher(place).matches() || NOT_A_PLACE.contains(firstWord(place))) {
            return null;
        }
        // Eine Kopfzeile ist eine Überschrift, kein Satz: kein Satzende, keine Telefonnummer.
        if (rest.endsWith(".") && rest.split("\\s+").length > 12) {
            return null;
        }
        if (rest.matches(".*\\d{3,}[ /-]?\\d{3,}.*")) {
            return null;
        }
        return matcher;
    }

    private static String firstWord(String place) {
        return place.split("[\\s/,]+")[0].toLowerCase(Locale.GERMAN);
    }

    private static String stripLead(String paragraph) {
        String text = paragraph.strip();
        text = DATELINE.matcher(text).replaceFirst("");
        text = PRESS_DATE.matcher(text).replaceFirst("");
        return text.strip();
    }

    private static List<String> withoutFooter(List<String> paragraphs) {
        List<String> kept = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String text = paragraph.strip();
            if (isFooter(text)) {
                break;
            }
            kept.add(text);
        }
        return kept;
    }

    private static boolean isFooter(String text) {
        String lower = text.toLowerCase(Locale.GERMAN);
        return lower.startsWith("rückfragen bitte an")
                || lower.startsWith("original-content von")
                || lower.matches("^[\\p{L} .\\-]{3,60},\\s*pressesprecher(in)?\\b.*")
                || lower.startsWith("polizeipräsidium mittelhessen\n")
                || lower.startsWith("polizeipräsidium mittelhessen, presse");
    }
}
