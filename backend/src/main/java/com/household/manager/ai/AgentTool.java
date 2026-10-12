package com.household.manager.ai;

import java.util.Map;

/**
 * Ein Werkzeug eines KI-Agenten: Definition (Name, Beschreibung, JSON-Schema) plus
 * Ausfuehrung. Ein Spring-Bean pro Tool; die Registry sammelt alle Beans ein und
 * filtert nach {@link #scope()}.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON-Schema der Eingabe im Anthropic-Format ("type": "object", ...). */
    Map<String, Object> inputSchema();

    /** Default TELEGRAM, damit die Bestandstools unveraendert bleiben. */
    default AgentScope scope() {
        return AgentScope.TELEGRAM;
    }

    String execute(Map<String, Object> input) throws Exception;
}
