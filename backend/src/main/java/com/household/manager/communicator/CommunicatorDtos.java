package com.household.manager.communicator;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

/** API-Vertrag des Communicators: eine Frage hinein, Karten heraus. */
public final class CommunicatorDtos {

    private CommunicatorDtos() {
    }

    public static final String CARD_CLIMATE = "climate";
    public static final String CARD_SWITCH = "switch";
    public static final String CARD_MODE = "mode";
    public static final String CARD_LOCK = "lock";
    public static final String CARD_INFO = "info";

    public static final String SUGGEST_THERMOSTAT_TARGET = "thermostat-target";
    public static final String SUGGEST_SWITCH = "switch";
    public static final String SUGGEST_MODE = "mode";
    public static final String SUGGEST_ASK = "ask";

    public record AskTextRequest(@NotBlank @Size(max = 500) String text) {
    }

    /** {@code transcript} ist der verstandene Text; bei einer Texteingabe die Eingabe selbst. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AskResponse(String transcript, List<Card> cards) {
    }

    public record StatusResponse(boolean enabled, boolean aiConfigured, boolean speechConfigured) {
    }

    /**
     * Eine Karte der Antwort. Welche Felder gesetzt sind, haengt an {@code type}; alle Werte hat der
     * Server aus dem Live-Zustand gelesen, nie das Modell geliefert. Fehlende Werte fehlen im JSON.
     */
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Card(
            String type,
            String title,
            String icon,
            /** Freitext, nur bei {@code info}. */
            String text,
            // climate
            Double temperature,
            Double humidity,
            Boolean stale,
            Long thermostatId,
            Double targetTemp,
            Boolean heating,
            String thermostatMode,
            // switch / mode
            String entityId,
            String state,
            Double powerWatts,
            // lock
            Long smartlockId,
            String doorState,
            List<Suggestion> suggestions) {
    }

    /** Ein vorgeschlagener naechster Schritt; ausgefuehrt wird er vom Frontend ueber die normalen Endpunkte. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Suggestion(
            String label,
            String kind,
            Long thermostatId,
            Double value,
            String entityId,
            String state,
            String question) {
    }
}
