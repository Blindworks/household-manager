package com.household.manager.telegram.tools;

import java.util.Map;

/**
 * Ein Werkzeug des Telegram-KI-Assistenten: Definition (Name, Beschreibung,
 * JSON-Schema) plus Ausführung. Ein Spring-Bean pro Tool; die Registry
 * sammelt alle Beans ein.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON-Schema der Eingabe im Anthropic-Format ("type": "object", ...). */
    Map<String, Object> inputSchema();

    /**
     * Führt das Tool aus. Rückgabe ist der Text/JSON-String für den
     * tool_result-Block; Exceptions fängt die Registry und meldet sie als
     * Fehler-Result an das Modell.
     */
    String execute(Map<String, Object> input) throws Exception;
}
