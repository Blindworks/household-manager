package com.household.manager.blaulicht.source;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Liest den Volltext einer Presseportal-Meldung. Maßgeblich ist nur das erste {@code article.story} —
 * die {@code article.news}-Blöcke darunter sind Anrisse fremder Meldungen. Die Absätze beginnen nach
 * der Ortsmarke „Giessen (ots)“; Kopf (Datum, Dienststelle) gehört nicht dazu. Den Abspann schneidet
 * erst der Splitter ab.
 */
public final class PresseportalDetailParser {

    /** Volltext; {@code publishedAt} aus {@code <time datetime>} (lokal), null wenn nicht lesbar. */
    public record Detail(String title, List<String> paragraphs, LocalDateTime publishedAt) {
    }

    private static final DateTimeFormatter TIME_ATTRIBUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PresseportalDetailParser() {
    }

    public static Optional<Detail> parse(String html) {
        Document document = Jsoup.parse(html);
        Element story = document.selectFirst("article.story");
        if (story == null) {
            return Optional.empty();
        }
        Element heading = story.selectFirst("h1");
        List<String> paragraphs = new ArrayList<>();
        boolean started = false;
        for (Element p : story.select("p")) {
            if (p.hasClass("date") || p.hasClass("customer")) {
                continue;
            }
            String text = textWithBreaks(p);
            if (!started) {
                if (text.contains("(ots)")) {
                    started = true;
                    String rest = text.substring(text.indexOf("(ots)") + 5).replaceFirst("^\\s*[-–]\\s*", "").strip();
                    if (!rest.isEmpty()) {
                        paragraphs.add(rest);
                    }
                }
                continue;
            }
            if (!text.isBlank()) {
                paragraphs.add(text);
            }
        }
        if (!started) {
            return Optional.empty();
        }
        return Optional.of(new Detail(heading == null ? "" : heading.text().trim(), paragraphs, time(story)));
    }

    private static LocalDateTime time(Element story) {
        Element time = story.selectFirst("time[datetime]");
        if (time == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(time.attr("datetime").trim(), TIME_ATTRIBUTE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Absatztext mit {@code <br>} als Zeilenumbruch, sonst wie {@code Element.text()}. */
    static String textWithBreaks(Element p) {
        String[] lines = p.html().split("(?i)<br\\s*/?>");
        List<String> texts = new ArrayList<>();
        for (String line : lines) {
            String text = Jsoup.parse(line).text().strip();
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }
        return String.join("\n", texts);
    }
}
