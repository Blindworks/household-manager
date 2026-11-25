package com.household.manager.blaulicht;

import com.household.manager.blaulicht.processing.BlaulichtProcessor;
import com.household.manager.blaulicht.processing.ProcessedCase;
import com.household.manager.blaulicht.source.RawReport;
import com.household.manager.repository.BlaulichtEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Speichert neue Fälle. Doppelt ist ein Fall, wenn Quelle und Id ({@code guid#n}) oder der
 * Inhalts-Fingerabdruck schon bekannt sind. Ein Fehler an einer Meldung reißt die anderen nicht mit.
 */
@Service
@Slf4j
public class BlaulichtIngestService {

    private final BlaulichtProcessor processor;
    private final BlaulichtEventRepository repository;
    private final Clock clock;

    public BlaulichtIngestService(BlaulichtProcessor processor, BlaulichtEventRepository repository, Clock clock) {
        this.processor = processor;
        this.repository = repository;
        this.clock = clock;
    }

    /** @return die neu gespeicherten Fälle */
    public List<BlaulichtEvent> ingest(List<RawReport> reports) {
        List<BlaulichtEvent> saved = new ArrayList<>();
        for (RawReport report : reports) {
            try {
                for (ProcessedCase processed : processor.process(report)) {
                    store(processed).ifPresent(saved::add);
                }
            } catch (RuntimeException e) {
                log.warn("Blaulicht: Meldung {} nicht verarbeitet: {}", report.guid(), e.getMessage());
            }
        }
        return saved;
    }

    private Optional<BlaulichtEvent> store(ProcessedCase processed) {
        if (repository.existsBySourceAndExternalId(processed.source(), processed.externalId())
                || repository.existsByContentHash(processed.contentHash())) {
            return Optional.empty();
        }
        try {
            return Optional.of(repository.save(toEntity(processed)));
        } catch (DataIntegrityViolationException e) {
            // Ein paralleler Lauf hat denselben Fall gerade gespeichert.
            return Optional.empty();
        }
    }

    private BlaulichtEvent toEntity(ProcessedCase processed) {
        BlaulichtEvent event = new BlaulichtEvent();
        event.setSource(processed.source());
        event.setExternalId(cut(processed.externalId(), BlaulichtEvent.EXTERNAL_ID_MAX));
        event.setContentHash(processed.contentHash());
        event.setCategory(processed.category());
        event.setLocality(cut(processed.locality(), BlaulichtEvent.LOCALITY_MAX));
        event.setNearby(processed.nearby());
        event.setRelevance(processed.relevance());
        event.setTitle(cut(processed.title(), BlaulichtEvent.TITLE_MAX));
        event.setSummary(processed.summary());
        event.setOccurredAt(processed.occurredAt());
        event.setOccurredUntil(processed.occurredUntil());
        event.setPublishedAt(processed.publishedAt().withNano(0));
        String url = processed.url();
        event.setUrl(url != null && url.length() <= BlaulichtEvent.URL_MAX ? url : null);
        event.setCreatedAt(LocalDateTime.now(clock).withNano(0));
        return event;
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
