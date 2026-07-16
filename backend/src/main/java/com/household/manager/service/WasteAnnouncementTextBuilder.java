package com.household.manager.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Baut den Ansagetext fuer die Abend-Erinnerung.
 *
 * <p>Bewusst neutral formuliert ("wird abgeholt: X") statt "die Biotonne wird geleert": Die
 * Bezeichnungen stammen woertlich aus dem Kalender, Genus und Artikel sind unbekannt
 * ("der Restmuell", "die Biotonne", "der Gelbe Sack").
 */
@Component
public class WasteAnnouncementTextBuilder {

    public String buildTomorrowText(List<String> labels) {
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("labels must not be empty");
        }
        return "Erinnerung: Morgen wird abgeholt: " + joinNaturally(labels) + ".";
    }

    private String joinNaturally(List<String> labels) {
        if (labels.size() == 1) {
            return labels.get(0);
        }
        String head = String.join(", ", labels.subList(0, labels.size() - 1));
        return head + " und " + labels.get(labels.size() - 1);
    }
}
