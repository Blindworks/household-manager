package com.household.manager.communicator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** API-Vertrag des Communicators: eine Frage hinein, Karten aus allgemeinen Bausteinen heraus. */
public final class CommunicatorDtos {

    private CommunicatorDtos() {
    }

    public record AskTextRequest(@NotBlank @Size(max = 500) String text) {
    }

    /** {@code transcript} ist der verstandene Text; bei einer Texteingabe die Eingabe selbst. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AskResponse(String transcript, List<Card> cards) {
    }

    public record StatusResponse(boolean enabled, boolean aiConfigured, boolean speechConfigured) {
    }

    /** {@code questions} sind Folgefragen, die ein Knopf wie eine gesprochene Frage stellt. */
    public record Card(String title, String icon, List<Block> blocks, List<Action> actions, List<String> questions) {
    }

    /**
     * Ein Baustein; welche Felder gesetzt sind, haengt an {@code type}: {@code text}, {@code value},
     * {@code list}, {@code chart}, {@code slider}. Werte stammen aus einer echten API-Antwort, nur
     * {@code text} und Beschriftungen vom Modell.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(String type, String label, String text, String value, String unit, String tone,
                        List<Item> items, List<Point> points,
                        Double number, Double min, Double max, Double step, Action action) {

        static Block text(String text) {
            return new Block("text", null, text, null, null, null, null, null, null, null, null, null, null);
        }

        static Block value(String label, String value, String unit, String tone) {
            return new Block("value", label, null, value, unit, tone, null, null, null, null, null, null, null);
        }

        static Block list(String label, List<Item> items) {
            return new Block("list", label, null, null, null, null, items, null, null, null, null, null, null);
        }

        static Block chart(String label, String unit, List<Point> points) {
            return new Block("chart", label, null, null, unit, null, null, points, null, null, null, null, null);
        }

        static Block slider(String label, String unit, double number, double min, double max, double step,
                            Action action) {
            return new Block("slider", label, null, null, unit, null, null, null, number, min, max, step, action);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String title, String subtitle, String value, String unit) {
    }

    public record Point(String label, double value) {
    }

    /**
     * Eine Aenderung, die erst der Tipp des Nutzers im Browser ausfuehrt (mit seiner Anmeldung). Der Server
     * hat geprueft, dass es den Endpunkt gibt. Bei einem Regler steht im Body {@code "$value"} als Platzhalter.
     * {@code confirm}: braucht einen zweiten Tipp.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String label, String method, String path, Map<String, String> query, JsonNode body,
                         boolean confirm) {
    }
}
