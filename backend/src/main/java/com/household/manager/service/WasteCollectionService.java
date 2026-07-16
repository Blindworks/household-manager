package com.household.manager.service;

import com.household.manager.dto.WasteCollectionEventResponse;
import com.household.manager.model.entity.WasteCollectionEvent;
import com.household.manager.repository.WasteCollectionEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Leseseite der Abholtermine. Die {@link Clock} wird injiziert, damit "heute" und "morgen"
 * in Tests deterministisch sind.
 */
@Service
@Slf4j
public class WasteCollectionService {

    private final WasteCollectionEventRepository repository;
    private final Clock clock;

    public WasteCollectionService(WasteCollectionEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * @param lookaheadDays Fenstergroesse in Tagen, einschliesslich heute (mindestens 1)
     */
    @Transactional(readOnly = true)
    public List<WasteCollectionEventResponse> getUpcoming(int lookaheadDays) {
        LocalDate from = today();
        LocalDate to = from.plusDays(Math.max(1, lookaheadDays) - 1L);
        return repository.findByCollectionDateBetweenOrderByCollectionDateAscLabelAsc(from, to)
                .stream()
                .map(event -> toResponse(event, from))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<String> getLabelsForTomorrow() {
        return repository.findByCollectionDateOrderByLabelAsc(today().plusDays(1))
                .stream()
                .map(WasteCollectionEvent::getLabel)
                .toList();
    }

    @Transactional(readOnly = true)
    public long countUpcoming() {
        return repository.countByCollectionDateGreaterThanEqual(today());
    }

    private WasteCollectionEventResponse toResponse(WasteCollectionEvent event, LocalDate from) {
        return WasteCollectionEventResponse.builder()
                .date(event.getCollectionDate())
                .label(event.getLabel())
                .daysUntil(ChronoUnit.DAYS.between(from, event.getCollectionDate()))
                .build();
    }
}
