package com.household.manager.blaulicht.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Liest die NINA-API (warnung.bund.de, {@code api31}). Das Dashboard ({@code dashboard/<ARS>.json})
 * listet Warnungen eines Kreises im Format der {@code mapData}-Listen ({@code id}, {@code type},
 * {@code startDate}, {@code i18nTitle.de}); die Detailseite ({@code warnings/<id>.json}) ist eine
 * CAP-Meldung. Defensiv: fehlende Felder sind null, nie geraten.
 */
public final class NinaParser {

    public record DashboardEntry(String id, String type, String title, LocalDateTime startDate) {

        public String provider() {
            int dot = id.indexOf('.');
            return (dot > 0 ? id.substring(0, dot) : id).toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Eine Warnung im Detail.
     *
     * @param areaCodes Verwaltungsbereiche ({@code warnVerwaltungsbereiche}), leer wenn die Meldung keine nennt
     */
    public record Warning(String id, String msgType, String headline, String description, String instruction,
                          String web, LocalDateTime sent, List<String> areaCodes) {
    }

    private NinaParser() {
    }

    public static List<DashboardEntry> parseDashboard(ObjectMapper mapper, String json, ZoneId zone)
            throws IOException {
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) {
            throw new IOException("Dashboard ist keine Liste");
        }
        List<DashboardEntry> entries = new ArrayList<>();
        for (JsonNode node : root) {
            String id = text(node.path("id"));
            if (id == null) {
                // Manche Antworten verschachteln die Meldung unter "payload".
                id = text(node.path("payload").path("id"));
            }
            if (id == null) {
                continue;
            }
            JsonNode payload = node.has("i18nTitle") ? node : node.path("payload").path("data");
            String title = text(payload.path("i18nTitle").path("de"));
            if (title == null) {
                title = text(payload.path("headline"));
            }
            String type = Optional.ofNullable(text(node.path("type")))
                    .orElse(text(node.path("payload").path("type")));
            entries.add(new DashboardEntry(id, type, title, time(text(node.path("startDate")), zone)));
        }
        return entries;
    }

    public static Optional<Warning> parseWarning(ObjectMapper mapper, String json, ZoneId zone) throws IOException {
        JsonNode root = mapper.readTree(json);
        String id = text(root.path("identifier"));
        JsonNode info = germanInfo(root.path("info"));
        if (id == null || info == null) {
            return Optional.empty();
        }
        List<String> areas = List.of();
        for (JsonNode parameter : info.path("parameter")) {
            if ("warnVerwaltungsbereiche".equals(text(parameter.path("valueName")))) {
                String value = text(parameter.path("value"));
                if (value != null) {
                    areas = Arrays.stream(value.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
                }
            }
        }
        return Optional.of(new Warning(id, text(root.path("msgType")), plain(text(info.path("headline"))),
                plain(text(info.path("description"))), plain(text(info.path("instruction"))),
                text(info.path("web")), time(text(root.path("sent")), zone), areas));
    }

    /**
     * Ob eine Warnung den Ort mit dem Schlüssel {@code localArs} betrifft: keine Gebietsliste (Kreis-Dashboard
     * hat schon gefiltert), der Schlüssel selbst oder ein übergeordneter (Kreis/Land, rechts mit Nullen
     * aufgefüllt).
     */
    public static boolean affects(Warning warning, String localArs) {
        if (warning.areaCodes().isEmpty()) {
            return true;
        }
        for (String code : warning.areaCodes()) {
            if (code.equals(localArs)) {
                return true;
            }
            String prefix = code.replaceAll("0+$", "");
            if (!prefix.isEmpty() && prefix.length() < code.length() && localArs.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode germanInfo(JsonNode infos) {
        JsonNode first = null;
        for (JsonNode info : infos) {
            if (first == null) {
                first = info;
            }
            String language = text(info.path("language"));
            if (language != null && language.toLowerCase(Locale.ROOT).startsWith("de")) {
                return info;
            }
        }
        return first;
    }

    private static String plain(String html) {
        if (html == null) {
            return null;
        }
        String withBreaks = html.replaceAll("(?i)<br\\s*/?>", "\n");
        StringBuilder text = new StringBuilder();
        for (String line : withBreaks.split("\n")) {
            String clean = Jsoup.parse(line).text().strip();
            if (!clean.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(clean);
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static LocalDateTime time(String value, ZoneId zone) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
