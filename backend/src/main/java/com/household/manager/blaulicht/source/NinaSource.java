package com.household.manager.blaulicht.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.blaulicht.BlaulichtCategory;
import com.household.manager.blaulicht.BlaulichtProperties;
import com.household.manager.blaulicht.BlaulichtSourceKey;
import com.household.manager.blaulicht.settings.BlaulichtSettings;
import com.household.manager.blaulicht.settings.BlaulichtSettingsService;
import com.household.manager.repository.BlaulichtEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * NINA-Warnungen (MoWaS, KatWarn, BIWAPP, Polizei, Hochwasser) für den Wetteraukreis, gefiltert auf
 * Bad Vilbel ({@link NinaParser#affects}). DWD-Wetter ist ausgeschlossen (das Dashboard hat es schon),
 * Entwarnungen ({@code Cancel}) ebenfalls. Je Warnungs-Id entsteht genau eine Zeile; Aktualisierungen
 * derselben Id werden nicht erneut gemeldet.
 */
@Component
@Slf4j
public class NinaSource implements BlaulichtSource {

    private final BlaulichtHttp http;
    private final BlaulichtProperties properties;
    private final BlaulichtEventRepository repository;
    private final BlaulichtSettingsService settings;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Set<String> seen = new HashSet<>();

    public NinaSource(BlaulichtHttp http, BlaulichtProperties properties, BlaulichtEventRepository repository,
                      BlaulichtSettingsService settings, ObjectMapper mapper, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.repository = repository;
        this.settings = settings;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public BlaulichtSourceKey key() {
        return BlaulichtSourceKey.NINA;
    }

    @Override
    public boolean enabled() {
        return settings.current().sources().ninaEnabled();
    }

    @Override
    public synchronized List<RawReport> fetchNew() throws BlaulichtFetchException {
        BlaulichtSettings current = settings.current();
        String dashboardUrl = properties.getNina().getBaseUrl() + "/dashboard/"
                + current.sources().dashboardArs() + ".json";
        Optional<String> dashboard = http.getIfChanged(dashboardUrl);
        if (dashboard.isEmpty()) {
            return List.of();
        }
        List<NinaParser.DashboardEntry> entries;
        try {
            entries = NinaParser.parseDashboard(mapper, dashboard.get(), clock.getZone());
        } catch (IOException e) {
            http.forget(dashboardUrl);
            throw new BlaulichtFetchException("NINA-Dashboard nicht lesbar: " + e.getMessage(), e);
        }
        List<RawReport> reports = new ArrayList<>();
        for (NinaParser.DashboardEntry entry : entries) {
            if (seen.contains(entry.id()) || isExcluded(entry, current.sources().excludedProviders())) {
                continue;
            }
            if (repository.existsBySourceAndExternalId(BlaulichtSourceKey.NINA, entry.id())) {
                seen.add(entry.id());
                continue;
            }
            try {
                detail(entry, current).ifPresent(reports::add);
                seen.add(entry.id());
            } catch (BlaulichtFetchException | IOException e) {
                http.forget(dashboardUrl);
                log.warn("NINA: Warnung {} nicht lesbar: {}", entry.id(), e.getMessage());
            }
        }
        return reports;
    }

    private static boolean isExcluded(NinaParser.DashboardEntry entry, List<String> excludedProviders) {
        return "cancel".equalsIgnoreCase(entry.type())
                || excludedProviders.stream()
                .anyMatch(p -> entry.provider().startsWith(p.toLowerCase(Locale.ROOT)));
    }

    private Optional<RawReport> detail(NinaParser.DashboardEntry entry, BlaulichtSettings current) throws BlaulichtFetchException, IOException {
        String url = properties.getNina().getBaseUrl() + "/warnings/" + entry.id() + ".json";
        Optional<NinaParser.Warning> parsed = NinaParser.parseWarning(mapper, http.get(url), clock.getZone());
        if (parsed.isEmpty() || "cancel".equalsIgnoreCase(parsed.get().msgType())
                || !NinaParser.affects(parsed.get(), current.sources().localArs())) {
            return Optional.empty();
        }
        NinaParser.Warning warning = parsed.get();
        String headline = Optional.ofNullable(warning.headline()).orElse(entry.title());
        if (headline == null) {
            return Optional.empty();
        }
        List<String> paragraphs = new ArrayList<>();
        Optional.ofNullable(warning.description()).ifPresent(paragraphs::add);
        Optional.ofNullable(warning.instruction()).ifPresent(paragraphs::add);
        LocalDateTime published = Optional.ofNullable(warning.sent())
                .or(() -> Optional.ofNullable(entry.startDate()))
                .orElseGet(() -> LocalDateTime.now(clock));
        String link = warning.web() != null && warning.web().startsWith("http")
                ? warning.web()
                : "https://warnung.bund.de/meldungen/" + entry.id();
        return Optional.of(new RawReport(BlaulichtSourceKey.NINA, entry.id(), headline, paragraphs, link,
                published, BlaulichtCategory.WARNUNG, current.localities().main()));
    }
}
