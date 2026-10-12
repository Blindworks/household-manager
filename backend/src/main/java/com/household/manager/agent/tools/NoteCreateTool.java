package com.household.manager.agent.tools;

import com.household.manager.agent.AgentNoteService;
import com.household.manager.agent.NoteKind;
import com.household.manager.agent.NoteSource;
import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import com.household.manager.model.entity.AgentNote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Der Agent merkt sich etwas; die Quelle ist hier immer AGENT, das Modell kann sich nicht als Nutzer ausgeben. */
@Component
@RequiredArgsConstructor
public class NoteCreateTool implements AgentTool {

    private final AgentNoteService noteService;
    private final Clock clock;

    @Override
    public String name() {
        return "note_create";
    }

    @Override
    public String description() {
        return "Merkt sich eine Beobachtung (mit Ablauf) oder eine Vorliebe (unbefristet). "
                + "Beobachtungen immer mit expiresInHours.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("type", "string", "enum", List.of("OBSERVATION", "PREFERENCE")),
                        "text", Map.of("type", "string", "maxLength", 500, "description", "Die Notiz, knapp formuliert"),
                        "expiresInHours", Map.of("type", "integer", "minimum", 1, "maximum", 8760,
                                "description", "Gueltigkeit in Stunden; bei Vorlieben weglassen")),
                "required", List.of("kind", "text"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        NoteKind kind;
        try {
            kind = NoteKind.valueOf(String.valueOf(input.get("kind")));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("kind muss OBSERVATION oder PREFERENCE sein.");
        }
        AgentNote note = noteService.create(kind, NoteInputs.text(input), NoteSource.AGENT,
                NoteInputs.expiresAt(input, clock));
        return "Notiz #" + note.getId() + " gespeichert.";
    }
}
