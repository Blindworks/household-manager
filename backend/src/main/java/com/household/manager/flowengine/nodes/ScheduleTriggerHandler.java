package com.household.manager.flowengine.nodes;

import com.household.manager.flowengine.FlowMessage;
import com.household.manager.flowengine.NodeContext;
import com.household.manager.flowengine.TriggerNodeHandler;
import com.household.manager.flowengine.model.NodeConfig;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

/**
 * Zeitplan-Trigger (Spring-Cron). Wird beim Deploy registriert; das
 * Cleanup-Runnable storniert den Job beim Undeploy/Re-Deploy.
 */
@Component
public class ScheduleTriggerHandler implements TriggerNodeHandler {

    @Override
    public String type() {
        return "schedule-trigger";
    }

    @Override
    public int outputPorts() {
        return 1;
    }

    @Override
    public Optional<String> watchedEntityId(NodeConfig config) {
        return Optional.empty();
    }

    @Override
    public List<String> validate(NodeConfig config) {
        Optional<String> cron = config.string("cron");
        if (cron.isEmpty()) {
            return List.of("cron fehlt");
        }
        if (!CronExpression.isValidExpression(cron.get())) {
            return List.of("cron ist kein gültiger Spring-Cron-Ausdruck: " + cron.get());
        }
        return List.of();
    }

    @Override
    public Runnable register(NodeConfig config, NodeContext ctx) {
        String cron = config.string("cron").orElseThrow();
        ScheduledFuture<?> future = ctx.scheduler().schedule(
                () -> ctx.emit(0, FlowMessage.of(Map.of(
                        "timestamp", LocalDateTime.now(),
                        "triggerNodeId", ctx.nodeId()))),
                new CronTrigger(cron));
        return () -> future.cancel(false);
    }
}
