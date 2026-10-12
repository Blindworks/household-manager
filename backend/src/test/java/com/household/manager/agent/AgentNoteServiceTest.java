package com.household.manager.agent;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AgentNote;
import com.household.manager.repository.AgentNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentNoteServiceTest {

    private static final Instant INSTANT = Instant.parse("2026-10-12T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(INSTANT, ZoneId.of("Europe/Berlin"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @Mock AgentNoteRepository repository;
    @Mock AuditService auditService;
    AgentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setMaxActiveNotes(2);
    }

    private AgentNoteService service() {
        return new AgentNoteService(repository, properties, auditService, CLOCK);
    }

    private static AgentNote note(long id, NoteKind kind, NoteSource source, LocalDateTime expiresAt) {
        return AgentNote.builder().id(id).kind(kind).text("Text " + id).source(source)
                .createdAt(NOW.minusDays(1)).updatedAt(NOW.minusDays(1)).expiresAt(expiresAt).build();
    }

    @Test
    void listActiveHidesExpired() {
        AgentNote expired = note(1, NoteKind.OBSERVATION, NoteSource.AGENT, NOW.minusHours(1));
        AgentNote open = note(2, NoteKind.PREFERENCE, NoteSource.USER, null);
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(expired, open));

        assertEquals(List.of(open), service().listActive());
    }

    @Test
    void createRejectsBlankAndTooLong() {
        assertThrows(IllegalArgumentException.class,
                () -> service().create(NoteKind.PREFERENCE, "  ", NoteSource.USER, null));
        assertThrows(IllegalArgumentException.class,
                () -> service().create(NoteKind.PREFERENCE, "x".repeat(501), NoteSource.USER, null));
        verify(repository, never()).save(any());
    }

    @Test
    void createRefusesWhenLimitReached() {
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(
                note(1, NoteKind.PREFERENCE, NoteSource.USER, null),
                note(2, NoteKind.PREFERENCE, NoteSource.USER, null)));

        assertThrows(IllegalStateException.class,
                () -> service().create(NoteKind.PREFERENCE, "neu", NoteSource.AGENT, null));
        verify(repository, never()).save(any());
    }

    @Test
    void createSavesWithTimestampsAndAudits() {
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());
        when(repository.save(any(AgentNote.class))).thenAnswer(i -> i.getArgument(0));

        AgentNote saved = service().create(NoteKind.OBSERVATION, "  Gast kommt  ", NoteSource.AGENT, NOW.plusHours(5));

        ArgumentCaptor<AgentNote> captor = ArgumentCaptor.forClass(AgentNote.class);
        verify(repository).save(captor.capture());
        AgentNote n = captor.getValue();
        assertSame(n, saved);
        assertEquals(NOW, n.getCreatedAt());
        assertEquals(NOW, n.getUpdatedAt());
        assertEquals(NoteSource.AGENT, n.getSource());
        assertEquals(NoteKind.OBSERVATION, n.getKind());
        assertEquals("Gast kommt", n.getText());
        assertEquals(NOW.plusHours(5), n.getExpiresAt());
        verify(auditService).record("agent.note.create", "OBSERVATION/AGENT: Gast kommt");
    }

    @Test
    void updateChangesTextAndKeepsKindAndSource() {
        AgentNote existing = note(3, NoteKind.PREFERENCE, NoteSource.USER, null);
        when(repository.findById(3L)).thenReturn(Optional.of(existing));
        when(repository.save(any(AgentNote.class))).thenAnswer(i -> i.getArgument(0));

        AgentNote result = service().update(3, " neuer Text ", NOW.plusHours(1), NoteSource.USER);

        assertEquals("neuer Text", result.getText());
        assertEquals(NoteKind.PREFERENCE, result.getKind());
        assertEquals(NoteSource.USER, result.getSource());
        assertEquals(NOW, result.getUpdatedAt());
        assertEquals(NOW.minusDays(1), result.getCreatedAt());
        assertEquals(NOW.plusHours(1), result.getExpiresAt());
        verify(auditService).record("agent.note.update", "#3: neuer Text");
    }

    @Test
    void updateRejectsUnknownIdAndBlankText() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service().update(9, "x", null, NoteSource.USER));
        assertThrows(IllegalArgumentException.class, () -> service().update(1, " ", null, NoteSource.USER));
        verify(repository, never()).save(any());
    }

    @Test
    void agentMayNotUpdateUserNote() {
        when(repository.findById(1L)).thenReturn(Optional.of(note(1, NoteKind.PREFERENCE, NoteSource.USER, null)));

        assertThrows(IllegalArgumentException.class,
                () -> service().update(1, "umgeschrieben", NOW.plusHours(1), NoteSource.AGENT));
        verify(repository, never()).save(any());
    }

    @Test
    void userMayUpdateAnyNote() {
        AgentNote agentNote = note(2, NoteKind.OBSERVATION, NoteSource.AGENT, null);
        when(repository.findById(2L)).thenReturn(Optional.of(agentNote));
        when(repository.save(any(AgentNote.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals("vom Nutzer", service().update(2, "vom Nutzer", null, NoteSource.USER).getText());
    }

    @Test
    void agentMayUpdateOwnNote() {
        when(repository.findById(2L)).thenReturn(Optional.of(note(2, NoteKind.OBSERVATION, NoteSource.AGENT, null)));
        when(repository.save(any(AgentNote.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals("neu", service().update(2, "neu", null, NoteSource.AGENT).getText());
    }

    @Test
    void agentMayNotDeleteUserNote() {
        when(repository.findById(1L)).thenReturn(Optional.of(note(1, NoteKind.PREFERENCE, NoteSource.USER, null)));

        assertThrows(IllegalArgumentException.class, () -> service().delete(1, NoteSource.AGENT));
        verify(repository, never()).deleteById(any());
    }

    @Test
    void userMayDeleteAnyNote() {
        when(repository.findById(1L)).thenReturn(Optional.of(note(1, NoteKind.PREFERENCE, NoteSource.AGENT, null)));

        service().delete(1, NoteSource.USER);

        verify(repository).deleteById(1L);
        verify(auditService).record("agent.note.delete", "#1 durch USER");
    }

    @Test
    void deleteUnknownIdFails() {
        when(repository.findById(7L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service().delete(7, NoteSource.USER));
    }

    @Test
    void purgeExpiredReturnsCount() {
        when(repository.deleteExpired(NOW)).thenReturn(3);
        assertEquals(3, service().purgeExpired());
    }

    @Test
    void purgeExpiredNeverThrows() {
        when(repository.deleteExpired(any())).thenThrow(new RuntimeException("db"));
        assertEquals(0, service().purgeExpired());
    }

    @Test
    void rendersPromptLines() {
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(
                note(1, NoteKind.PREFERENCE, NoteSource.USER, null),
                note(2, NoteKind.OBSERVATION, NoteSource.AGENT, LocalDateTime.of(2026, 10, 13, 8, 30))));

        String[] lines = service().renderForPrompt().split("\n");

        assertEquals(2, lines.length);
        assertEquals("#1 [PREFERENCE, USER, unbefristet] Text 1", lines[0]);
        assertEquals("#2 [OBSERVATION, AGENT, bis 2026-10-13 08:30] Text 2", lines[1]);
    }

    @Test
    void rendersPlaceholderWhenEmpty() {
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of());
        assertEquals("(keine Notizen)", service().renderForPrompt());
    }
}
