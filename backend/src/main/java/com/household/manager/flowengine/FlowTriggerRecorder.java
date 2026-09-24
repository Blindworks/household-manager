package com.household.manager.flowengine;

import com.household.manager.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Hält fest, wann ein Flow zuletzt ausgelöst hat (Spalte {@code flows.last_triggered_at},
 * angezeigt in der Flow-Übersicht). Wirft nie: ein DB-Fehler darf den Flow-Lauf,
 * den er protokolliert, nicht abbrechen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlowTriggerRecorder {

    private final FlowRepository flowRepository;
    private final Clock clock;

    public void recordTriggered(long flowId) {
        try {
            flowRepository.updateLastTriggeredAt(flowId, LocalDateTime.now(clock));
        } catch (Exception ex) {
            log.warn("Flow {}: Ausloesezeitpunkt konnte nicht gespeichert werden: {}", flowId, ex.getMessage());
        }
    }
}
