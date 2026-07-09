package com.household.manager.flowengine;

import com.household.manager.entitystate.EntityStateChangedEvent;
import com.household.manager.flowengine.model.NodeConfig;

import java.util.Optional;

/**
 * Trigger-Nodes: kein Eingang; feuern über ctx.emit(0, msg).
 */
public interface TriggerNodeHandler extends NodeHandler {

    /** Entity, auf die dieser Trigger lauscht (für den Trigger-Index); leer bei schedule-trigger. */
    Optional<String> watchedEntityId(NodeConfig config);

    /** Reaktion auf ein Entity-Event (nur für den Trigger relevanter Entitäten aufgerufen). */
    default void onEntityEvent(EntityStateChangedEvent event, NodeConfig config, NodeContext ctx) {
    }

    /**
     * Beim Deploy aufgerufen (z. B. Cron registrieren).
     *
     * @return Cleanup, das beim Undeploy/Re-Deploy ausgeführt wird
     */
    default Runnable register(NodeConfig config, NodeContext ctx) {
        return () -> {
        };
    }

    /** Trigger haben keinen Eingang. */
    @Override
    default NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        return NodeResult.none();
    }
}
