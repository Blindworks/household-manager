package com.household.manager.blaulicht;

import com.household.manager.blaulicht.dto.BlaulichtBriefingDto;
import com.household.manager.blaulicht.dto.BlaulichtEventDto;
import com.household.manager.repository.BlaulichtEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Liste, Nacht und Briefing — alle Leser fragen diese Klasse (Hub, Dialog, Entitäten, Jobs). */
@Service
public class BlaulichtQueryService {

    static final int DEFAULT_LIST_DAYS = 7;
    static final int MAX_LIST = 200;
    /** Eine Tatzeit liegt höchstens so lange vor dem Nachtbeginn und zählt trotzdem (Zeitraum über die Nacht). */
    private static final int CANDIDATE_DAYS_BEFORE = 1;

    private static final Comparator<BlaulichtEvent> BY_TIME = Comparator
            .comparing((BlaulichtEvent e) -> e.getOccurredAt() != null ? e.getOccurredAt() : e.getPublishedAt())
            .reversed();

    private final BlaulichtEventRepository repository;
    private final BlaulichtProperties properties;
    private final Clock clock;

    public BlaulichtQueryService(BlaulichtEventRepository repository, BlaulichtProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BlaulichtEventDto> list(LocalDateTime since, BlaulichtCategory category, String locality) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime from = since != null ? since : now.minusDays(DEFAULT_LIST_DAYS);
        LocalDateTime evening = currentNight(now).start();
        return repository.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(from).stream()
                .filter(e -> category == null || e.getCategory() == category)
                .filter(e -> locality == null || locality.isBlank()
                        || (e.getLocality() != null && e.getLocality().toLowerCase(Locale.GERMAN)
                        .contains(locality.trim().toLowerCase(Locale.GERMAN))))
                .limit(MAX_LIST)
                .map(e -> toDto(e, evening))
                .toList();
    }

    /** Fälle der laufenden bzw. zuletzt vergangenen Nacht, jüngste Tatzeit zuerst. */
    @Transactional(readOnly = true)
    public List<BlaulichtEventDto> overnight() {
        NightWindow night = currentNight(LocalDateTime.now(clock));
        return nightEvents(night).stream().map(e -> toDto(e, night.start())).toList();
    }

    @Transactional(readOnly = true)
    public BlaulichtBriefingDto briefing() {
        LocalDateTime now = LocalDateTime.now(clock);
        NightWindow night = currentNight(now);
        List<BlaulichtEvent> candidates = candidates(night);
        List<BlaulichtEvent> inNight = candidates.stream().filter(night::contains).sorted(BY_TIME).toList();
        List<BlaulichtEvent> morning = inNight.stream().filter(e -> e.getPublishedAt().isBefore(night.end())).toList();
        List<BlaulichtEvent> addendum = inNight.stream().filter(e -> !e.getPublishedAt().isBefore(night.end())).toList();
        int olderNew = (int) candidates.stream()
                .filter(e -> !night.contains(e) && !e.getPublishedAt().isBefore(night.start()))
                .filter(e -> !e.isNearby())
                .count();
        BlaulichtProperties.Briefing config = properties.getBriefing();
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        boolean cardVisible = !time.isBefore(config.getNightEnd()) && time.isBefore(config.getCardUntil());
        boolean addendumVisible = cardVisible && !time.isBefore(config.getAddendumAt()) && !addendum.isEmpty();
        String main = properties.getLocalities().getMain();
        return new BlaulichtBriefingDto(night.start(), night.end(),
                BlaulichtBriefingText.morning(morning, olderNew, main),
                (int) morning.stream().filter(e -> !e.isNearby()).count(),
                (int) morning.stream().filter(BlaulichtEvent::isNearby).count(),
                olderNew,
                BlaulichtBriefingText.addendum(addendum),
                addendum.size(),
                cardVisible && today.equals(night.end().toLocalDate()),
                addendumVisible && today.equals(night.end().toLocalDate()),
                inNight.stream().map(e -> toDto(e, night.start())).toList());
    }

    NightWindow currentNight(LocalDateTime now) {
        return NightWindow.current(now, properties.getBriefing());
    }

    private List<BlaulichtEvent> nightEvents(NightWindow night) {
        return candidates(night).stream().filter(night::contains).sorted(BY_TIME).toList();
    }

    private List<BlaulichtEvent> candidates(NightWindow night) {
        return repository.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(
                night.start().minusDays(CANDIDATE_DAYS_BEFORE));
    }

    static BlaulichtEventDto toDto(BlaulichtEvent event, LocalDateTime evening) {
        return new BlaulichtEventDto(event.getId(), event.getSource(), event.getCategory(), event.getLocality(),
                event.isNearby(), event.getTitle(), event.getSummary(), event.getOccurredAt(), event.getOccurredUntil(),
                event.getPublishedAt(), event.getUrl(), event.getRelevance(),
                !event.getPublishedAt().isBefore(evening));
    }
}
