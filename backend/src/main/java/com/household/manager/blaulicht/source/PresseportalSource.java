package com.household.manager.blaulicht.source;

import com.household.manager.blaulicht.BlaulichtProperties;
import com.household.manager.blaulicht.BlaulichtSourceKey;
import com.household.manager.blaulicht.processing.LocalityMatcher;
import com.household.manager.repository.BlaulichtEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Presseportal Blaulicht, Dienststelle 154582 (Polizeipräsidium Mittelhessen – Pressestelle, ganz
 * Mittelhessen; die frühere Wetterau-Dienststelle 43647 meldet seit 06/2025 nichts mehr).
 *
 * <p>Der Feed trägt nur einen Anriss, der Volltext steht auf der Detailseite. Gelesen wird eine
 * Detailseite nur, wenn der Titel {@code titleFilter} enthält („WETTERAUKREIS“) oder Titel bzw. Anriss
 * einen konfigurierten Ort nennen, nur für noch unbekannte Meldungen und höchstens
 * {@code maxDetailFetchesPerRun} je Lauf — der Rest folgt im nächsten Lauf. Bereits gesehene Meldungen
 * (auch solche ohne Bad-Vilbel-Fall, die keine Zeile ergeben) merkt sich die Quelle im Speicher; nach
 * einem Neustart werden sie höchstens einmal erneut gelesen.
 */
@Component
@Slf4j
public class PresseportalSource implements BlaulichtSource {

    private static final int SEEN_LIMIT = 2000;

    private final BlaulichtHttp http;
    private final BlaulichtProperties properties;
    private final BlaulichtEventRepository repository;
    private final LocalityMatcher localities;
    private final Clock clock;
    private final Set<String> seen = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SEEN_LIMIT;
        }
    });

    public PresseportalSource(BlaulichtHttp http, BlaulichtProperties properties,
                              BlaulichtEventRepository repository, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.repository = repository;
        this.localities = new LocalityMatcher(properties.getLocalities());
        this.clock = clock;
    }

    @Override
    public BlaulichtSourceKey key() {
        return BlaulichtSourceKey.PRESSEPORTAL;
    }

    @Override
    public boolean enabled() {
        return properties.getPresseportal().isEnabled();
    }

    @Override
    public synchronized List<RawReport> fetchNew() throws BlaulichtFetchException {
        String feedUrl = properties.getPresseportal().getFeedUrl();
        Optional<String> feed = http.getIfChanged(feedUrl);
        if (feed.isEmpty()) {
            return List.of();
        }
        List<PresseportalRssParser.Item> items = PresseportalRssParser.parse(feed.get(), clock.getZone());
        if (items.isEmpty() && !feed.get().contains("<rss")) {
            http.forget(feedUrl);
            throw new BlaulichtFetchException("Feed nicht lesbar");
        }
        List<RawReport> reports = new ArrayList<>();
        int budget = properties.getPresseportal().getMaxDetailFetchesPerRun();
        int failures = 0;
        for (PresseportalRssParser.Item item : items) {
            if (seen.contains(item.guid())) {
                continue;
            }
            if (!isCandidate(item) || repository.existsForGuid(BlaulichtSourceKey.PRESSEPORTAL, item.guid())) {
                seen.add(item.guid());
                continue;
            }
            if (budget-- <= 0) {
                // Der Feed kommt beim nächsten Lauf unverändert: ohne Validatoren erneut voll lesen.
                http.forget(feedUrl);
                break;
            }
            try {
                fetchDetail(item).ifPresent(reports::add);
                seen.add(item.guid());
            } catch (BlaulichtFetchException e) {
                failures++;
                http.forget(feedUrl);
                log.warn("Presseportal: Detailseite {} nicht lesbar: {}", item.url(), e.getMessage());
            }
        }
        if (failures > 0 && reports.isEmpty()) {
            throw new BlaulichtFetchException("Detailseiten nicht lesbar (" + failures + ")");
        }
        return reports;
    }

    private boolean isCandidate(PresseportalRssParser.Item item) {
        String filter = properties.getPresseportal().getTitleFilter();
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return item.title().toLowerCase(Locale.GERMAN).contains(filter.toLowerCase(Locale.GERMAN))
                || localities.mentionsAny(item.title() + "\n" + item.teaser());
    }

    private Optional<RawReport> fetchDetail(PresseportalRssParser.Item item) throws BlaulichtFetchException {
        Optional<PresseportalDetailParser.Detail> detail = PresseportalDetailParser.parse(http.get(item.url()));
        if (detail.isEmpty()) {
            log.warn("Presseportal: Detailseite {} ohne erkennbaren Meldungstext", item.url());
            return Optional.empty();
        }
        String title = detail.get().title().length() > item.title().length() ? detail.get().title() : item.title();
        return Optional.of(new RawReport(BlaulichtSourceKey.PRESSEPORTAL, item.guid(), title,
                detail.get().paragraphs(), item.url(), item.publishedAt(), null, null));
    }
}
