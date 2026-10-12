package com.household.manager.agent.tools;

import com.household.manager.agent.AgentNoteService;
import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import com.household.manager.model.entity.AgentNote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Ersetzt Text und Ablauf einer vorhandenen Notiz (Id aus dem Notizblock im Prompt). */
@Component
@RequiredArgsConstructor
public class NoteUpdateTool implements AgentTool {

    private final AgentNoteService noteService;
    private final Clock clock;

    @Override
    public String name() {
        return "note_update";
    }

    @Override
    public String description() {
        return "Aktualisiert Text und Ablauf einer vorhandenen Notiz anhand ihrer Id. "
                + "Ohne expiresInHours wird die Notiz unbefristet.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "integer", "description", "Id der Notiz (#<id>)"),
                        "text", Map.of("type", "string", "maxLength", 500),
                        "expiresInHours", Map.of("type", "integer", "minimum", 1, "maximum", 8760)),
                "required", List.of("id", "text"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        AgentNote note = noteService.update(NoteInputs.id(input), NoteInputs.text(input),
                NoteInputs.expiresAt(input, clock));
        return "Notiz #" + note.getId() + " aktualisiert.";
    }
}
