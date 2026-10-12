package com.household.manager.agent.tools;

import java.util.Map;

/** Gemeinsame Eingabe-Auswertung der Aktions-Tools, damit Pflichtfelder überall gleich geprüft werden. */
final class ActionInputs {

    static final Map<String, Object> REASON_PROPERTY = Map.of("type", "string",
            "description", "Begründung in einem Satz, deutsch");

    private ActionInputs() {
    }

    /** Pflicht-Textfeld; leer oder fehlend ist ein Fehler, den das Modell lesen und korrigieren kann. */
    static String required(Map<String, Object> input, String field) {
        Object raw = input.get(field);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalArgumentException(field + " fehlt.");
        }
        return String.valueOf(raw).trim();
    }

    static String reason(Map<String, Object> input) {
        return required(input, "reason");
    }
}
