package com.household.manager.agent.tools;

import com.household.manager.agent.AgentNoteService;
import com.household.manager.agent.NoteSource;
import com.household.manager.ai.AgentScope;
import com.household.manager.ai.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Loescht eine Notiz; der Service verweigert dem Agenten das Loeschen von Nutzernotizen. */
@Component
@RequiredArgsConstructor
public class NoteDeleteTool implements AgentTool {

    private final AgentNoteService noteService;

    @Override
    public String name() {
        return "note_delete";
    }

    @Override
    public String description() {
        return "Loescht eine eigene Notiz anhand ihrer Id. Notizen des Nutzers koennen nicht geloescht werden.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of("type", "integer", "description", "Id der Notiz (#<id>)")),
                "required", List.of("id"));
    }

    @Override
    public AgentScope scope() {
        return AgentScope.HOUSE;
    }

    @Override
    public String execute(Map<String, Object> input) {
        long id = NoteInputs.id(input);
        noteService.delete(id, NoteSource.AGENT);
        return "Notiz #" + id + " geloescht.";
    }
}
