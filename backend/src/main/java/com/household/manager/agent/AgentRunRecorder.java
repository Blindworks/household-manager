package com.household.manager.agent;

import com.household.manager.model.entity.AgentRun;
import com.household.manager.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Schreibt das Laufprotokoll und liefert den Tagesverbrauch an Tokens (Grundlage des Budgets).
 * {@link #record} wirft nie: ein Protokollfehler darf den Lauf nicht nachträglich zum Fehlschlag machen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRunRecorder {

    static final int SUMMARY_MAX = 2000;
    static final int ERROR_MAX = 1000;

    private final AgentRunRepository repository;
    private final Clock clock;

    public void record(AgentTrigger trigger, AgentRunResult result, String model, LocalDateTime startedAt) {
        try {
            repository.save(AgentRun.builder()
                    .triggerType(trigger.type())
                    .triggerDetail(truncate(trigger.describe(), 1000))
                    .startedAt(startedAt)
                    .durationMs(result.durationMs())
                    .model(model)
                    .inputTokens(result.inputTokens())
                    .outputTokens(result.outputTokens())
                    .toolCalls(result.toolCalls())
                    .summary(truncate(result.summary(), SUMMARY_MAX))
                    .error(truncate(result.error(), ERROR_MAX))
                    .build());
        } catch (Exception ex) {
            log.warn("Laufprotokoll des Hausverstands nicht speicherbar: {}", ex.getMessage());
        }
    }

    public List<AgentRun> recent() {
        return repository.findTop50ByOrderByStartedAtDesc();
    }

    /** {input, output} seit Mitternacht (Haushaltszeit); 0/0 ohne Läufe. */
    public long[] tokensToday() {
        List<Object[]> rows = repository.sumTokensSince(LocalDate.now(clock).atStartOfDay());
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return new long[]{0, 0};
        }
        Object[] row = rows.get(0);
        return new long[]{toLong(row, 0), toLong(row, 1)};
    }

    private static long toLong(Object[] row, int index) {
        return row.length > index && row[index] instanceof Number n ? n.longValue() : 0L;
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
