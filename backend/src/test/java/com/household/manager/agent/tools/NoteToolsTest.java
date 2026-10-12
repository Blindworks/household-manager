package com.household.manager.agent.tools;

import com.household.manager.agent.AgentNoteService;
import com.household.manager.agent.NoteKind;
import com.household.manager.agent.NoteSource;
import com.household.manager.ai.AgentScope;
import com.household.manager.model.entity.AgentNote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoteToolsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneId.of("Europe/Berlin"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Mock AgentNoteService service;

    @Test
    void noteCreateConvertsHoursToExpiresAt() throws Exception {
        when(service.create(any(), any(), any(), any())).thenReturn(AgentNote.builder().id(12L).build());

        String result = new NoteCreateTool(service, CLOCK).execute(
                Map.of("kind", "OBSERVATION", "text", "Gast da", "expiresInHours", 24));

        verify(service).create(NoteKind.OBSERVATION, "Gast da", NoteSource.AGENT, NOW.plusHours(24));
        assertTrue(result.contains("12"));
    }

    @Test
    void noteCreateWithoutExpiryIsUnlimited() throws Exception {
        when(service.create(any(), any(), any(), any())).thenReturn(AgentNote.builder().id(1L).build());

        new NoteCreateTool(service, CLOCK).execute(Map.of("kind", "PREFERENCE", "text", "Mag es warm"));

        verify(service).create(NoteKind.PREFERENCE, "Mag es warm", NoteSource.AGENT, null);
    }

    @Test
    void noteCreateRejectsOutOfRangeHoursAndBadKind() {
        NoteCreateTool tool = new NoteCreateTool(service, CLOCK);
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("kind", "OBSERVATION", "text", "x", "expiresInHours", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("kind", "OBSERVATION", "text", "x", "expiresInHours", 8761)));
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(Map.of("kind", "FOO", "text", "x")));
        verifyNoInteractions(service);
    }

    @Test
    void noteUpdateCallsServiceWithExpiry() throws Exception {
        when(service.update(anyLong(), any(), any())).thenReturn(AgentNote.builder().id(4L).build());

        String result = new NoteUpdateTool(service, CLOCK).execute(
                Map.of("id", 4, "text", "neu", "expiresInHours", 2));

        verify(service).update(4L, "neu", NOW.plusHours(2));
        assertTrue(result.contains("4"));
    }

    @Test
    void noteDeleteCallsServiceAsAgent() throws Exception {
        String result = new NoteDeleteTool(service).execute(Map.of("id", 5));

        verify(service).delete(5L, NoteSource.AGENT);
        assertTrue(result.contains("5"));
    }

    @Test
    void allToolsAreHouseScoped() {
        assertEquals(AgentScope.HOUSE, new NoteCreateTool(service, CLOCK).scope());
        assertEquals(AgentScope.HOUSE, new NoteUpdateTool(service, CLOCK).scope());
        assertEquals(AgentScope.HOUSE, new NoteDeleteTool(service).scope());
    }

    @Test
    void toolNames() {
        assertEquals("note_create", new NoteCreateTool(service, CLOCK).name());
        assertEquals("note_update", new NoteUpdateTool(service, CLOCK).name());
        assertEquals("note_delete", new NoteDeleteTool(service).name());
    }
}
