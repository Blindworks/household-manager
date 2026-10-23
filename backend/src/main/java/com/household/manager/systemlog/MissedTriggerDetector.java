package com.household.manager.systemlog;

import com.household.manager.flowengine.FlowRegistry;
import com.household.manager.flowengine.FlowRunRecorder;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.FlowDefinitionParser;
import com.household.manager.flowengine.model.FlowNode;
import com.household.manager.model.entity.Flow;
import com.household.manager.repository.FlowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Protokolliert beim Start Cron-/Sonnen-Termine, die während eines Backend-Ausfalls fällig gewesen
 * wären (Status MISSED). Nachgefeuert wird nichts. Grundlage ist das Lebenszeichen des
 * {@link SystemHeartbeat}; ohne es (Erststart) gibt es nichts zu melden. Wirft nie.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MissedTriggerDetector {

    static final Duration MAX_LOOKBACK = Duration.ofDays(7);
    static final int MAX_PER_FLOW = 50;
    static final String SUMMARY_MESSAGE = "weitere verpasste Termine ausgelassen";
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final SystemHeartbeat heartbeat;
    private final SystemLogWriter writer;
    private final FlowRepository flowRepository;
    private final FlowDefinitionParser parser;
    private final FlowRegistry registry;
    private final FlowRunRecorder flowRunRecorder;
    private final Clock clock;

    public void detectMissed() {
        try {
            Instant lastAlive = heartbeat.lastAliveBeforeStart().orElse(null);
            if (lastAlive == null) {
                return;
            }
            ZonedDateTime now = ZonedDateTime.now(clock);
            Instant lowerBound = now.toInstant().minus(MAX_LOOKBACK);
            ZonedDateTime from = (lastAlive.isAfter(lowerBound) ? lastAlive : lowerBound).atZone(clock.getZone());
            String reason = "Backend lief nicht von " + DISPLAY.format(lastAlive.atZone(clock.getZone()))
                    + " bis " + DISPLAY.format(now);
            for (Flow flow : flowRepository.findByEnabledTrueAndDeployedDefinitionNotNull()) {
                detectForFlow(flow, from, now, reason);
            }
        } catch (Exception ex) {
            log.warn("Verpasste Termine konnten nicht ermittelt werden: {}", ex.getMessage());
        }
    }

    private void detectForFlow(Flow flow, ZonedDateTime from, ZonedDateTime now, String reason) {
        try {
            List<FlowNode> nodes = parser.parse(flow.getDeployedDefinition()).nodes();
            for (FlowNode node : nodes) {
                if (registry.handler(node.type()) instanceof TriggerNodeHandler trigger) {
                    reportNode(flow, trigger, node, from, now, reason);
                }
            }
        } catch (Exception ex) {
            log.warn("Flow {}: verpasste Termine nicht ermittelbar: {}", flow.getId(), ex.getMessage());
        }
    }

    private void reportNode(Flow flow, TriggerNodeHandler trigger, FlowNode node,
                            ZonedDateTime from, ZonedDateTime now, String reason) {
        try {
            List<ZonedDateTime> due = trigger.occurrencesBetween(node.config(), from, now, MAX_PER_FLOW + 1);
            String name = flowRunRecorder.flowName(flow.getId());
            for (ZonedDateTime at : due.subList(0, Math.min(due.size(), MAX_PER_FLOW))) {
                writer.write(entry(flow, name, at.toLocalDateTime(), reason));
            }
            if (due.size() > MAX_PER_FLOW) {
                writer.write(entry(flow, name, now.toLocalDateTime(), SUMMARY_MESSAGE));
            }
        } catch (Exception ex) {
            log.warn("Flow {} / Node {}: verpasste Termine nicht ermittelbar: {}", flow.getId(), node.id(), ex.getMessage());
        }
    }

    private static SystemLogEvent entry(Flow flow, String name, LocalDateTime occurredAt, String message) {
        return SystemLogEvent.builder()
                .occurredAt(occurredAt)
                .kind(SystemLogKind.FLOW_RUN)
                .status(SystemLogStatus.MISSED)
                .source(String.valueOf(flow.getId()))
                .title(name)
                .message(message)
                .flowId(flow.getId())
                .build();
    }
}
