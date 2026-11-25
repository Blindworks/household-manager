package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.BlaulichtCategory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Schlüsselwort-Klassifizierer. Die Überschrift entscheidet zuerst (Vorrang Brand, Einbruch, Unfall,
 * Diebstahl); nennt sie nur einen Diebstahl, der Text aber einen Einbruch, ist es ein Einbruch. Ohne
 * Treffer in der Überschrift entscheidet der Text, sonst „Sonstiges“.
 *
 * <p>Die Muster sind auf Wortanfänge mit erlaubten Endungen gebaut: „Brandstiftung“ und „Wohnungsbrand“
 * treffen, „Brandström-Straße“ und „Brandenburg“ nicht. „Feuerwehr“ allein ist kein Brand (sie rückt
 * auch zu Unfällen und Ölspuren aus), „erbeutet“ allein kein Diebstahl (Betrug).
 *
 * <p>Ein Fall, dessen Überschrift nur einen Rettungsdienst-Einsatz nennt und sonst nichts, wird verworfen.
 */
@Component
public class KeywordBlaulichtClassifier implements BlaulichtClassifier {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    /** Ganzes Wort; {@code \\p{L}*} vor einem Stamm erlaubt Zusammensetzungen („Wohnungseinbruch“). */
    private static final String WORD = "(?<![\\p{L}])(?:%s)(?![\\p{L}])";

    private static final Map<BlaulichtCategory, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        PATTERNS.put(BlaulichtCategory.BRAND, word(
                "\\p{L}*brand(?:e|es|s)?(?:stift\\p{L}*|schaden|schäden|ort|melder\\p{L}*|ursache|geruch|herd|einsatz)?"
                        + "|\\p{L}*brände|\\p{L}*feuers?|flammen|abgebrannt|ausgebrannt|niedergebrannt|verbrannt"
                        + "|rauchentwicklung|brannte|brannten|brennend\\p{L}*|brennt"));
        PATTERNS.put(BlaulichtCategory.EINBRUCH, word(
                "\\p{L}*einbruch\\p{L}*|\\p{L}*einbrüche\\p{L}*|einbrecher\\p{L}*|eingebrochen|einzubrechen"
                        + "|aufhebel\\p{L}*|aufzuhebeln|aufgehebelt|aufbruch\\p{L}*|aufbrüche|aufgebrochen|aufzubrechen"
                        + "|eingedrungen|einzudringen|eingestiegen|einzusteigen"));
        PATTERNS.put(BlaulichtCategory.UNFALL, word(
                "\\p{L}*unfall\\p{L}*|\\p{L}*unfälle\\p{L}*|zusammenstoß\\p{L}*|zusammengestoßen|kollision\\p{L}*"
                        + "|kollidiert\\p{L}*|touchiert\\p{L}*|aufgefahren|überschlug\\p{L}*|überschlagen"));
        PATTERNS.put(BlaulichtCategory.DIEBSTAHL, word(
                "\\p{L}*diebstahl\\p{L}*|\\p{L}*diebstähle\\p{L}*|\\p{L}*dieb(?:e|en|in)?|gestohlen|stahl|stahlen"
                        + "|entwendet\\p{L}*|entwenden|geklaut|klauten"));
    }

    private static Pattern word(String alternatives) {
        return Pattern.compile(String.format(WORD, alternatives), FLAGS);
    }

    private static final Pattern RESCUE_ONLY = word(
            "rettungsdienst\\p{L}*|krankenwagen|rettungswagen|notarzt\\p{L}*|rettungshubschrauber");

    @Override
    public Optional<BlaulichtCategory> classify(String headline, String body) {
        String head = headline == null ? "" : headline;
        String text = body == null ? "" : body;
        Optional<BlaulichtCategory> fromHeadline = firstMatch(head, BlaulichtCategory.BRAND, BlaulichtCategory.EINBRUCH,
                BlaulichtCategory.UNFALL, BlaulichtCategory.DIEBSTAHL);
        if (fromHeadline.isPresent()) {
            if (fromHeadline.get() == BlaulichtCategory.DIEBSTAHL && matches(BlaulichtCategory.EINBRUCH, text)) {
                return Optional.of(BlaulichtCategory.EINBRUCH);
            }
            return fromHeadline;
        }
        if (find(RESCUE_ONLY, head)) {
            return Optional.empty();
        }
        return Optional.of(firstMatch(text, BlaulichtCategory.EINBRUCH, BlaulichtCategory.BRAND,
                BlaulichtCategory.UNFALL, BlaulichtCategory.DIEBSTAHL).orElse(BlaulichtCategory.SONSTIGES));
    }

    private static Optional<BlaulichtCategory> firstMatch(String text, BlaulichtCategory... order) {
        for (BlaulichtCategory category : order) {
            if (matches(category, text)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    private static boolean matches(BlaulichtCategory category, String text) {
        return find(PATTERNS.get(category), text);
    }

    private static boolean find(Pattern pattern, String text) {
        return pattern.matcher(text).find();
    }
}
