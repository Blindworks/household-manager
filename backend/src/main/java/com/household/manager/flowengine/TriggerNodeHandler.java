package com.household.manager.flowengine;

import com.household.manager.entitystate.EntityEventFired;
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
     * Reaktion auf ein Ereignis einer EVENT-Entität. Wird — anders als
     * {@link #onEntityEvent} — bei JEDEM Ereignis aufgerufen, auch bei
     * wiederholt gleicher Aktion.
     */
    default void onEntityEventFired(EntityEventFired event, NodeConfig config, NodeContext ctx) {
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

    /**
     * Geplante Termine im Fenster {@code (from, to]}, aufsteigend, höchstens {@code limit}.
     * Nur für zeitgesteuerte Trigger sinnvoll (Cron, Sonne); das Systemprotokoll fragt damit,
     * was während eines Backend-Ausfalls verpasst wurde. Ereignis-Trigger liefern leer.
     */
    default java.util.List<java.time.ZonedDateTime> occurrencesBetween(NodeConfig config,
            java.time.ZonedDateTime from, java.time.ZonedDateTime to, int limit) {
        return java.util.List.of();
    }

    /** Trigger haben keinen Eingang. */
    @Override
    default NodeResult handle(FlowMessage message, NodeConfig config, NodeContext ctx) {
        return NodeResult.none();
    }
}
