package com.household.manager.agent;

import java.util.Map;

/** Eine vom Modell gewuenschte Aktion, typisiert und mit Begruendung; nur die Schleuse fuehrt sie aus. */
public record AgentAction(ActionClass actionClass, String target, Map<String, Object> params,
                          String reason, boolean urgent) {

    public Double doubleParam(String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " muss eine Zahl sein: " + value);
        }
    }

    public String stringParam(String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    public String describe() {
        return switch (actionClass) {
            case HEATING -> "Heizung " + target
                    + (doubleParam("targetTemp") != null ? " auf " + doubleParam("targetTemp") + " °C" : "")
                    + (stringParam("mode") != null ? " Modus " + stringParam("mode") : "");
            case SWITCH -> "Schalter " + target + " " + stringParam("state");
            case MODE -> "Modus " + target + " " + stringParam("state");
            case NOTIFY -> "Nachricht (" + target + "): " + stringParam("message");
            case LOCK -> "Schloss " + target + " verriegeln";
        };
    }

    /**
     * Beschreibung fuer Notizen mit Nutzer-Autoritaet: wie {@link #describe()}, aber ohne jeden
     * freien Modelltext. Bei einer Nachricht steckt der Inhalt im Parameter {@code message} — landete
     * er in einer USER-Notiz, haette sich das Modell eine Anweisung mit Nutzerrang geschrieben.
     */
    public String describeForNote() {
        if (actionClass == ActionClass.NOTIFY) {
            return "Nachricht an " + target;
        }
        return describe();
    }
}
