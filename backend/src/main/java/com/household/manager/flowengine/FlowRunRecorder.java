package com.household.manager.flowengine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import com.household.manager.systemlog.SystemLogEvent;
import com.household.manager.systemlog.SystemLogKind;
import com.household.manager.systemlog.SystemLogStatus;
import com.household.manager.systemlog.SystemLogWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uebersetzt einen abgeschlossenen Flow-Lauf in einen Systemprotokoll-Eintrag. Wirft nie.
 * Flow-Namen werden zwischengespeichert, damit der Flow-Executor nicht bei jedem Lauf die
 * DB liest; {@link #forgetName} haelt den Cache nach einer Umbenennung aktuell.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlowRunRecorder {

    private static final String TEST_SUFFIX = " (Test)";

    private final SystemLogWriter writer;
    private final FlowRepository flowRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<Long, String> flowNames = new ConcurrentHashMap<>();

    public void record(long flowId, String runId, SystemLogStatus status, List<FlowRunTrace.Step> steps,
                       boolean continuation, boolean injected) {
        try {
            String title = flowName(flowId) + (injected ? TEST_SUFFIX : "");
            writer.write(SystemLogEvent.builder()
                    .occurredAt(LocalDateTime.now(clock))
                    .kind(SystemLogKind.FLOW_RUN)
                    .status(status)
                    .source(String.valueOf(flowId))
                    .title(title)
                    .message(summary(status, steps, continuation))
                    .detailsJson(objectMapper.writeValueAsString(steps))
                    .flowId(flowId)
                    .runId(runId)
                    .build());
        } catch (Exception ex) {
            log.warn("Flow {}: Lauf konnte nicht protokolliert werden: {}", flowId, ex.getMessage());
        }
    }

    /** Nach Umbenennen oder Loeschen eines Flows aufrufen, damit der naechste Lauf den neuen Namen liest. */
    public void forgetName(long flowId) {
        flowNames.remove(flowId);
    }

    /** Anzeigename eines Flows aus dem Cache bzw. der DB, ersatzweise „Flow <id>“. Wirft nie. */
    public String flowName(long flowId) {
        String cached = flowNames.get(flowId);
        if (cached != null) {
            return cached;
        }
        try {
            String name = flowRepository.findById(flowId).map(Flow::getName).orElse(null);
            if (name != null && !name.isBlank()) {
                flowNames.put(flowId, name);
                return name;
            }
        } catch (Exception ex) {
            log.debug("Flow {}: Name nicht lesbar: {}", flowId, ex.getMessage());
        }
        return "Flow " + flowId;
    }

    private static String summary(SystemLogStatus status, List<FlowRunTrace.Step> steps, boolean continuation) {
        String prefix = continuation ? "Fortsetzung: " : "";
        FlowRunTrace.Step decisive = steps.stream()
                .filter(step -> FlowRunTrace.statusOf(step.outcome()) == status)
                .findFirst()
                .orElse(null);
        if (status == SystemLogStatus.OK || decisive == null) {
            return prefix + executed(steps.size());
        }
        String at = "‚" + displayName(decisive) + "‘";
        return prefix + switch (status) {
            case ERROR -> "Fehler an " + at + (decisive.error() != null ? ": " + decisive.error() : "");
            case STOPPED -> "gestoppt an " + at;
            case RATE_LIMITED -> "gedrosselt an " + at;
            case WAITING -> "wartet an " + at;
            default -> executed(steps.size());
        };
    }

    private static String executed(int nodes) {
        return "ausgeführt (" + nodes + (nodes == 1 ? " Node)" : " Nodes)");
    }

    private static String displayName(FlowRunTrace.Step step) {
        return step.label() != null && !step.label().isBlank() ? step.label() : step.nodeId();
    }
}
