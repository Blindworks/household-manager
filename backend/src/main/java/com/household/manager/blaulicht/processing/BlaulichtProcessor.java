package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.BlaulichtCategory;
import com.household.manager.blaulicht.BlaulichtProperties;
import com.household.manager.blaulicht.source.RawReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Macht aus einer Quellmeldung die gespeicherten Fälle: aufteilen ({@link BulletinSplitter}), Ort prüfen
 * ({@link LocalityMatcher}, ohne Treffer verworfen), kategorisieren ({@link BlaulichtClassifier}, leer ⇒
 * Rettungsdienst, verworfen), Tatzeit lesen ({@link OccurredAtExtractor}), Relevanz und Fingerabdruck.
 * Hat die Quelle Kategorie und Ort schon festgelegt (NINA), bleibt es ein einziger Fall.
 *
 * <p>Relevanz = Ortsrelevanz (Hauptort/Ortsteil oder Nachbarort) + Gewicht der Kategorie.
 */
@Component
public class BlaulichtProcessor {

    static final int SUMMARY_MAX = 4000;

    private static final Map<BlaulichtCategory, Integer> CATEGORY_WEIGHT = new EnumMap<>(Map.of(
            BlaulichtCategory.WARNUNG, 20,
            BlaulichtCategory.BRAND, 15,
            BlaulichtCategory.EINBRUCH, 15,
            BlaulichtCategory.UNFALL, 10,
            BlaulichtCategory.DIEBSTAHL, 5,
            BlaulichtCategory.SONSTIGES, 0));

    private final LocalityMatcher localities;
    private final BlaulichtClassifier classifier;
    private final int mainRelevance;

    public BlaulichtProcessor(BlaulichtProperties properties, BlaulichtClassifier classifier) {
        this.localities = new LocalityMatcher(properties.getLocalities());
        this.classifier = classifier;
        this.mainRelevance = properties.getLocalities().getMainRelevance();
    }

    public List<ProcessedCase> process(RawReport report) {
        if (report.fixedCategory() != null && report.fixedLocality() != null) {
            String body = String.join("\n\n", report.paragraphs());
            return List.of(build(report, report.guid(), report.title(), body,
                    new LocalityMatch(report.fixedLocality(), mainRelevance, false), report.fixedCategory()));
        }
        List<BulletinCase> cases = BulletinSplitter.split(report.title(), report.paragraphs());
        List<ProcessedCase> result = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            BulletinCase bulletinCase = cases.get(i);
            Optional<LocalityMatch> locality = localities.match(bulletinCase.placeHeader(), bulletinCase.headline(),
                    bulletinCase.body());
            if (locality.isEmpty()) {
                continue;
            }
            Optional<BlaulichtCategory> category = report.fixedCategory() != null
                    ? Optional.of(report.fixedCategory())
                    : classifier.classify(bulletinCase.headline(), bulletinCase.body());
            if (category.isEmpty()) {
                continue;
            }
            result.add(build(report, report.guid() + "#" + (i + 1), bulletinCase.headline(), bulletinCase.body(),
                    locality.get(), category.get()));
        }
        return result;
    }

    private ProcessedCase build(RawReport report, String externalId, String headline, String body,
                                LocalityMatch locality, BlaulichtCategory category) {
        Optional<Occurrence> occurrence = OccurredAtExtractor.extract(headline + "\n" + body, report.publishedAt());
        return new ProcessedCase(report.source(), externalId, ContentHasher.hash(headline, body, locality.locality()),
                category, locality.locality(), locality.nearby(),
                locality.relevance() + CATEGORY_WEIGHT.getOrDefault(category, 0),
                headline, truncate(body, SUMMARY_MAX),
                occurrence.map(Occurrence::from).orElse(null), occurrence.map(Occurrence::until).orElse(null),
                report.publishedAt(), report.url());
    }

    static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1).stripTrailing() + "…";
    }
}
