package com.household.manager.blaulicht.source;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Liest den RSS-Feed einer Presseportal-Dienststelle. Einträge ohne Link oder Datum werden übersprungen. */
public final class PresseportalRssParser {

    /** Ein Feed-Eintrag; {@code teaser} ist der rund 250 Zeichen lange Anriss, nie der Volltext. */
    public record Item(String guid, String title, String teaser, String url, LocalDateTime publishedAt) {
    }

    private PresseportalRssParser() {
    }

    public static List<Item> parse(String xml, ZoneId zone) {
        Document document = Jsoup.parse(xml, "", Parser.xmlParser());
        List<Item> items = new ArrayList<>();
        for (Element item : document.select("item")) {
            String url = text(item, "link");
            String guid = text(item, "guid");
            if (guid.isBlank()) {
                guid = url;
            }
            LocalDateTime published = parseDate(text(item, "pubDate"), zone);
            if (guid.isBlank() || published == null) {
                continue;
            }
            items.add(new Item(guid, text(item, "title"), Jsoup.parse(text(item, "description")).text(),
                    url.isBlank() ? guid : url, published));
        }
        return items;
    }

    private static String text(Element item, String tag) {
        Element element = item.selectFirst(tag);
        return element == null ? "" : element.text().trim();
    }

    private static LocalDateTime parseDate(String value, ZoneId zone) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .withZoneSameInstant(zone).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
