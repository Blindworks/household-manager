package com.household.manager.blaulicht;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sätze für Briefing und Nachtrag — einzige Formulierung. Bad Vilbel und Ortsteile werden nach Kategorie
 * und Ort gezählt („1 Einbruch in Dortelweil, 2 Unfälle in Bad Vilbel“), Nachbarorte nur als Anzahl.
 */
public final class BlaulichtBriefingText {

    private static final Map<BlaulichtCategory, String[]> NOUNS = new EnumMap<>(Map.of(
            BlaulichtCategory.BRAND, new String[]{"Brand", "Brände"},
            BlaulichtCategory.EINBRUCH, new String[]{"Einbruch", "Einbrüche"},
            BlaulichtCategory.UNFALL, new String[]{"Unfall", "Unfälle"},
            BlaulichtCategory.DIEBSTAHL, new String[]{"Diebstahl", "Diebstähle"},
            BlaulichtCategory.WARNUNG, new String[]{"Warnung", "Warnungen"},
            BlaulichtCategory.SONSTIGES, new String[]{"sonstige Meldung", "sonstige Meldungen"}));

    private BlaulichtBriefingText() {
    }

    public static String morning(List<BlaulichtEvent> night, int olderNewCount, String mainLocality) {
        String counts = counts(night);
        StringBuilder text = new StringBuilder(counts.isEmpty()
                ? "Heute Nacht war es in " + mainLocality + " ruhig."
                : "Heute Nacht: " + counts + ".");
        long nearby = night.stream().filter(BlaulichtEvent::isNearby).count();
        if (nearby > 0) {
            text.append(" Dazu ").append(nearby).append(nearby == 1 ? " Meldung" : " Meldungen")
                    .append(" aus Nachbarorten.");
        }
        if (olderNewCount > 0) {
            text.append(" Außerdem ").append(olderNewCount)
                    .append(olderNewCount == 1 ? " neue Meldung zu früheren Tagen." : " neue Meldungen zu früheren Tagen.");
        }
        return text.toString();
    }

    /** Leer, wenn es keinen Nachtrag gibt. */
    public static String addendum(List<BlaulichtEvent> addendum) {
        if (addendum.isEmpty()) {
            return "";
        }
        String counts = counts(addendum);
        long nearby = addendum.stream().filter(BlaulichtEvent::isNearby).count();
        StringBuilder text = new StringBuilder("Nachtrag zur Nacht: ");
        if (!counts.isEmpty()) {
            text.append(counts);
            if (nearby > 0) {
                text.append(", dazu ").append(nearby).append(" aus Nachbarorten");
            }
        } else {
            text.append(nearby).append(nearby == 1 ? " Meldung" : " Meldungen").append(" aus Nachbarorten");
        }
        return text.append('.').toString();
    }

    static String noun(BlaulichtCategory category, long count) {
        return NOUNS.get(category)[count == 1 ? 0 : 1];
    }

    private static String counts(List<BlaulichtEvent> events) {
        Map<BlaulichtCategory, Map<String, Long>> grouped = new EnumMap<>(BlaulichtCategory.class);
        for (BlaulichtEvent event : events) {
            if (event.isNearby()) {
                continue;
            }
            grouped.computeIfAbsent(event.getCategory(), c -> new LinkedHashMap<>())
                    .merge(event.getLocality(), 1L, Long::sum);
        }
        StringBuilder text = new StringBuilder();
        grouped.forEach((category, places) -> places.forEach((place, count) -> {
            if (!text.isEmpty()) {
                text.append(", ");
            }
            text.append(count).append(' ').append(noun(category, count)).append(" in ").append(place);
        }));
        return text.toString();
    }
}
