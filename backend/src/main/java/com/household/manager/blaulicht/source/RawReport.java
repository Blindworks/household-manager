package com.household.manager.blaulicht.source;

import com.household.manager.blaulicht.BlaulichtCategory;
import com.household.manager.blaulicht.BlaulichtSourceKey;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Eine Meldung, wie eine Quelle sie liefert — noch nicht aufgeteilt, gefiltert oder kategorisiert.
 *
 * @param guid           stabile Id der Quelle (Presseportal: Link der Meldung, NINA: Warnungs-Id)
 * @param paragraphs     Absätze des Volltexts in Reihenfolge
 * @param publishedAt    Veröffentlichung (Haushaltszeit)
 * @param fixedCategory  von der Quelle vorgegebene Kategorie (NINA: WARNUNG), sonst null ⇒ Klassifizierer
 * @param fixedLocality  von der Quelle geprüfter Ort (NINA: Gebietsabgleich), sonst null ⇒ Ortsfilter
 */
public record RawReport(
        BlaulichtSourceKey source,
        String guid,
        String title,
        List<String> paragraphs,
        String url,
        LocalDateTime publishedAt,
        BlaulichtCategory fixedCategory,
        String fixedLocality
) {
}
