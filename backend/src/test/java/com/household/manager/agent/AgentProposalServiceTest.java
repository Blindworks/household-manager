package com.household.manager.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.repository.AgentProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentProposalServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 12, 0);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AgentProposalRepository repository;
    private AgentActionExecutor executor;
    private AgentNoteService noteService;
    private AgentNotifier notifier;
    private AgentQuietHours quietHours;
    private AuditService auditService;
    private AgentProperties properties;
    private Clock clock;

    @BeforeEach
    void setUp() {
        repository = mock(AgentProposalRepository.class);
        executor = mock(AgentActionExecutor.class);
        noteService = mock(AgentNoteService.class);
        notifier = mock(AgentNotifier.class);
        quietHours = mock(AgentQuietHours.class);
        auditService = mock(AuditService.class);
        properties = new AgentProperties();
        properties.setMaxOpenProposals(3);
        properties.setProposalTtlHours(2);
        clock = Clock.fixed(ZonedDateTime.of(NOW, BERLIN).toInstant(), BERLIN);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.claim(anyLong(), any(), any(), any(), any())).thenReturn(1);
        when(repository.markDelivered(anyLong(), any(), any())).thenReturn(1);
    }

    private AgentProposalService service() {
        return new AgentProposalService(repository, executor, noteService, notifier, quietHours,
                properties, objectMapper, auditService, clock);
    }

    private AgentAction heating21() {
        return heating21(false);
    }

    private AgentAction heating21(boolean urgent) {
        return new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 21.0), "Wohnzimmer ist kalt", urgent);
    }

    private AgentProposal pending(long id) {
        return AgentProposal.builder().id(id).actionClass(ActionClass.HEATING).target("3")
                .actionJson(AgentActionJson.write(objectMapper, heating21())).reason("Wohnzimmer ist kalt")
                .status(ProposalStatus.PENDING).createdAt(NOW.minusMinutes(30)).deliveredAt(NOW.minusMinutes(30))
                .build();
    }

    // --- propose ---

    @Test
    void proposeOutsideQuietHoursSendsImmediately() {
        when(quietHours.isQuietNow()).thenReturn(false);

        AgentProposal p = service().propose(heating21());

        assertEquals(ProposalStatus.PENDING, p.getStatus());
        assertEquals(NOW, p.getDeliveredAt());
        assertEquals(NOW, p.getCreatedAt());
        assertEquals(ActionClass.HEATING, p.getActionClass());
        assertEquals("3", p.getTarget());
        assertEquals("Wohnzimmer ist kalt", p.getReason());
        assertEquals(heating21(), AgentActionJson.read(objectMapper, p.getActionJson()));
        verify(notifier).sendProposal(p);
    }

    @Test
    void proposeInQuietHoursWithoutUrgentIsHeldBack() {
        when(quietHours.isQuietNow()).thenReturn(true);

        AgentProposal p = service().propose(heating21());

        assertEquals(ProposalStatus.PENDING, p.getStatus());
        assertNull(p.getDeliveredAt());
        verify(notifier, never()).sendProposal(any());
        verify(repository).save(p);
    }

    @Test
    void urgentIgnoresQuietHours() {
        when(quietHours.isQuietNow()).thenReturn(true);

        AgentProposal p = service().propose(heating21(true));

        assertTrue(p.isUrgent());
        assertNotNull(p.getDeliveredAt());
        verify(notifier).sendProposal(p);
    }

    // --- decide ---

    @Test
    void decideApproveExecutesAndMarksExecuted() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "ok"));

        service().decide(1L, ProposalDecision.APPROVE, "Benedikt");

        assertEquals(ProposalStatus.EXECUTED, p.getStatus());
        assertEquals("Benedikt", p.getDecidedBy());
        assertEquals(NOW, p.getDecidedAt());
        assertNull(p.getError());
        verify(repository).claim(1L, ProposalStatus.PENDING, ProposalStatus.APPROVED, "Benedikt", NOW);
        verify(executor).execute(heating21());
        verify(notifier).markDecided(eq(p), contains("Benedikt"));
        verify(auditService).record(eq("agent.proposal.decide"), contains("APPROVE"));
        verifyNoInteractions(noteService);
    }

    @Test
    void decideApproveWhenAlreadySatisfiedSaysSo() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        when(executor.execute(any())).thenReturn(new ActionOutcome(true, "schon so"));

        service().decide(1L, ProposalDecision.APPROVE, "Benedikt");

        assertEquals(ProposalStatus.EXECUTED, p.getStatus());
        verify(notifier).markDecided(p, "War bereits so");
    }

    @Test
    void decideApproveWithDeviceFailureMarksFailed() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        when(executor.execute(any())).thenThrow(new RuntimeException("Hub weg"));

        service().decide(1L, ProposalDecision.APPROVE, "Benedikt");

        assertEquals(ProposalStatus.FAILED, p.getStatus());
        assertTrue(p.getError().contains("Hub weg"));
        verify(notifier).markDecided(eq(p), contains("Fehlgeschlagen"));
        verify(repository).save(p);
    }

    @Test
    void failingExecutorIsSavedAsFailedBeforeButtonsAreRemoved() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        when(executor.execute(any())).thenThrow(new IllegalStateException("Hub weg"));
        ArgumentCaptor<AgentProposal> saved = ArgumentCaptor.forClass(AgentProposal.class);
        ProposalStatus[] statusAtSave = new ProposalStatus[1];
        when(repository.save(saved.capture())).thenAnswer(inv -> {
            statusAtSave[0] = inv.<AgentProposal>getArgument(0).getStatus();
            return inv.getArgument(0);
        });

        service().decide(1L, ProposalDecision.APPROVE, "Benedikt");

        assertEquals(ProposalStatus.FAILED, statusAtSave[0]);
        InOrder order = inOrder(repository, executor, auditService, notifier);
        order.verify(repository).claim(1L, ProposalStatus.PENDING, ProposalStatus.APPROVED, "Benedikt", NOW);
        order.verify(executor).execute(any());
        order.verify(repository).save(p);
        order.verify(auditService).record(eq("agent.proposal.decide"), anyString());
        order.verify(notifier).markDecided(eq(p), contains("Fehlgeschlagen"));
    }

    @Test
    void secondDecisionLosesRaceWhenClaimReturnsZero() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        when(repository.claim(anyLong(), any(), any(), any(), any())).thenReturn(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service().decide(1L, ProposalDecision.APPROVE_AND_REMEMBER, "Benedikt"));

        assertTrue(ex.getMessage().contains("bereits entschieden"));
        verifyNoInteractions(executor, noteService, notifier, auditService);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectClaimsAsRejected() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        service().decide(1L, ProposalDecision.REJECT, "Benedikt");

        verify(repository).claim(1L, ProposalStatus.PENDING, ProposalStatus.REJECTED, "Benedikt", NOW);
    }

    @Test
    void decideRejectCreatesObservationNote() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        service().decide(1L, ProposalDecision.REJECT, "Benedikt");

        assertEquals(ProposalStatus.REJECTED, p.getStatus());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(noteService).create(eq(NoteKind.OBSERVATION), text.capture(), eq(NoteSource.USER),
                eq(LocalDateTime.of(2026, 10, 13, 12, 0)));
        assertEquals("Benedikt hat abgelehnt: Heizung 3 auf 21.0 °C", text.getValue());
        assertFalse(text.getValue().contains("Wohnzimmer ist kalt"));
        verify(notifier).markDecided(eq(p), contains("Abgelehnt"));
        verifyNoInteractions(executor);
    }

    @Test
    void approveAndRememberCreatesPreference() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "ok"));

        service().decide(1L, ProposalDecision.APPROVE_AND_REMEMBER, "Benedikt");

        assertEquals(ProposalStatus.EXECUTED, p.getStatus());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(noteService).create(eq(NoteKind.PREFERENCE), text.capture(), eq(NoteSource.USER), isNull());
        assertTrue(text.getValue().contains("stimmt zu"));
        assertEquals("Benedikt stimmt zu: Heizung 3 auf 21.0 °C", text.getValue());
        // Die Begruendung ist freier Modelltext und darf keine Nutzer-Autoritaet bekommen.
        assertFalse(text.getValue().contains("Wohnzimmer ist kalt"));
    }

    @Test
    void decisionNoteUsesTruncatedDecidedBy() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        service().decide(1L, ProposalDecision.REJECT, "x".repeat(150));

        verify(noteService).create(eq(NoteKind.OBSERVATION), startsWith("x".repeat(100) + " hat abgelehnt"),
                eq(NoteSource.USER), any());
    }

    @Test
    void secondDecisionIsRefused() {
        AgentProposal p = pending(1L);
        p.setStatus(ProposalStatus.EXECUTED);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        assertThrows(IllegalStateException.class, () -> service().decide(1L, ProposalDecision.APPROVE, "x"));

        verifyNoInteractions(executor, notifier);
        verify(repository, never()).claim(anyLong(), any(), any(), any(), any());
    }

    @Test
    void unknownProposalIsRefused() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service().decide(99L, ProposalDecision.APPROVE, "x"));
    }

    @Test
    void decidedByIsTruncatedToColumnLength() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        service().decide(1L, ProposalDecision.REJECT, "x".repeat(150));

        assertEquals(100, p.getDecidedBy().length());
    }

    // --- expireStale ---

    @Test
    void expireStaleMarksOldPendingAndRemovesButtons() {
        AgentProposal old = pending(1L);
        old.setCreatedAt(LocalDateTime.of(2026, 10, 12, 9, 0));
        old.setDeliveredAt(LocalDateTime.of(2026, 10, 12, 9, 0));
        AgentProposal fresh = pending(2L);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(old, fresh));

        assertEquals(1, service().expireStale());

        assertEquals(ProposalStatus.EXPIRED, old.getStatus());
        assertEquals(ProposalStatus.PENDING, fresh.getStatus());
        verify(notifier).markDecided(eq(old), contains("Verfallen"));
        verify(notifier, never()).markDecided(eq(fresh), any());
        verify(repository).claim(1L, ProposalStatus.PENDING, ProposalStatus.EXPIRED, "system", NOW);
        verify(repository, never()).claim(eq(2L), any(), any(), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void expireStaleSkipsRowWhenClaimReturnsZero() {
        AgentProposal old = pending(1L);
        old.setCreatedAt(LocalDateTime.of(2026, 10, 12, 9, 0));
        old.setDeliveredAt(LocalDateTime.of(2026, 10, 12, 9, 0));
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(old));
        when(repository.claim(anyLong(), any(), any(), any(), any())).thenReturn(0);

        assertEquals(0, service().expireStale());

        assertEquals(ProposalStatus.PENDING, old.getStatus());
        verifyNoInteractions(notifier);
        verify(repository, never()).save(any());
    }

    @Test
    void expireStaleCountsFromDeliveryNotCreation() {
        // Nachts angelegt, erst vor einer Stunde zugestellt: die Frist (2 h) laeuft ab der Zustellung.
        AgentProposal p = pending(1L);
        p.setCreatedAt(LocalDateTime.of(2026, 10, 11, 23, 0));
        p.setDeliveredAt(NOW.minusHours(1));
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(p));

        assertEquals(0, service().expireStale());

        assertEquals(ProposalStatus.PENDING, p.getStatus());
        verify(repository, never()).claim(anyLong(), any(), any(), any(), any());
    }

    @Test
    void heldProposalCreatedAt23IsDeliveredAt0701NotExpired() {
        clock = Clock.fixed(ZonedDateTime.of(LocalDateTime.of(2026, 10, 12, 7, 1), BERLIN).toInstant(), BERLIN);
        AgentProposal held = pending(1L);
        held.setCreatedAt(LocalDateTime.of(2026, 10, 11, 23, 0));
        held.setDeliveredAt(null);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(held));
        when(executor.isSatisfied(any())).thenReturn(false);
        AgentProposalService service = service();

        // Reihenfolge wie im AgentMaintenanceJob: erst verfallen lassen, dann nachreichen.
        assertEquals(0, service.expireStale());
        assertEquals(1, service.deliverPending());

        assertEquals(ProposalStatus.PENDING, held.getStatus());
        verify(repository, never()).claim(eq(1L), eq(ProposalStatus.PENDING), eq(ProposalStatus.EXPIRED), any(), any());
        verify(notifier).sendProposal(held);
        verify(notifier, never()).markDecided(any(), any());
    }

    @Test
    void expireStaleNeverThrows() {
        when(repository.findByStatus(any())).thenThrow(new RuntimeException("DB weg"));

        assertEquals(0, assertDoesNotThrow(() -> service().expireStale()));
    }

    // --- deliverPending ---

    @Test
    void deliverPendingSkipsAlreadySatisfied() {
        AgentProposal held = pending(1L);
        held.setDeliveredAt(null);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(held));
        when(executor.isSatisfied(any())).thenReturn(true);

        assertEquals(1, service().deliverPending());

        assertEquals(ProposalStatus.EXECUTED, held.getStatus());
        assertEquals("system", held.getDecidedBy());
        assertEquals(NOW, held.getDecidedAt());
        assertNull(held.getError());
        verify(notifier, never()).sendProposal(any());
        verify(executor, never()).execute(any());
        verify(repository).claim(1L, ProposalStatus.PENDING, ProposalStatus.EXECUTED, "system", NOW);
        verify(repository, never()).save(any());
    }

    @Test
    void deliverPendingSkipsWhenClaimLost() {
        AgentProposal held = pending(1L);
        held.setDeliveredAt(null);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(held));
        when(executor.isSatisfied(any())).thenReturn(true);
        when(repository.claim(anyLong(), any(), any(), any(), any())).thenReturn(0);

        assertEquals(0, service().deliverPending());

        assertEquals(ProposalStatus.PENDING, held.getStatus());
        verifyNoInteractions(notifier);
        verify(repository, never()).save(any());
    }

    @Test
    void deliverPendingDoesNotSendWhenMarkDeliveredReturnsZero() {
        AgentProposal held = pending(1L);
        held.setDeliveredAt(null);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(held));
        when(executor.isSatisfied(any())).thenReturn(false);
        when(repository.markDelivered(anyLong(), any(), any())).thenReturn(0);

        assertEquals(0, service().deliverPending());

        assertNull(held.getDeliveredAt());
        verifyNoInteractions(notifier);
        verify(repository, never()).save(any());
    }

    @Test
    void deliverPendingSendsWhenStillRelevant() {
        AgentProposal held = pending(1L);
        held.setDeliveredAt(null);
        AgentProposal alreadyDelivered = pending(2L);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(held, alreadyDelivered));
        when(executor.isSatisfied(any())).thenReturn(false);

        assertEquals(1, service().deliverPending());

        assertEquals(ProposalStatus.PENDING, held.getStatus());
        assertEquals(NOW, held.getDeliveredAt());
        verify(notifier).sendProposal(held);
        verify(notifier, never()).sendProposal(alreadyDelivered);
        verify(repository).markDelivered(1L, ProposalStatus.PENDING, NOW);
        verify(repository, never()).markDelivered(eq(2L), any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void deliverPendingWaitsDuringQuietHours() {
        when(quietHours.isQuietNow()).thenReturn(true);

        assertEquals(0, service().deliverPending());

        verifyNoInteractions(notifier, executor);
    }

    @Test
    void deliverPendingLeavesProposalPendingWhenCheckFails() {
        AgentProposal broken = pending(1L);
        broken.setDeliveredAt(null);
        AgentProposal ok = pending(2L);
        ok.setDeliveredAt(null);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(broken, ok));
        when(executor.isSatisfied(any())).thenThrow(new RuntimeException("Hub weg")).thenReturn(false);

        assertEquals(1, assertDoesNotThrow(() -> service().deliverPending()));

        assertEquals(ProposalStatus.PENDING, broken.getStatus());
        assertNull(broken.getDeliveredAt());
        verify(notifier).sendProposal(ok);
    }

    // --- executeDirect ---

    @Test
    void executeDirectSendsInfoOnlyWhenSomethingHappened() {
        when(executor.execute(any())).thenReturn(new ActionOutcome(true, "schon so"));

        AgentProposal p = service().executeDirect(heating21());

        assertEquals(ProposalStatus.EXECUTED, p.getStatus());
        verify(notifier, never()).sendInfo(any());
    }

    @Test
    void executeDirectRecordsExecutionAndInforms() {
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "ok"));

        AgentProposal p = service().executeDirect(heating21());

        assertEquals(ProposalStatus.EXECUTED, p.getStatus());
        assertEquals("system", p.getDecidedBy());
        assertEquals(NOW, p.getDecidedAt());
        assertNull(p.getError());
        verify(repository).save(p);
        verify(notifier).sendInfo(contains("Heizung 3 auf 21.0 °C"));
    }

    @Test
    void executeDirectFailureIsRecordedAndReported() {
        when(executor.execute(any())).thenThrow(new RuntimeException("Hub weg"));

        AgentProposal p = service().executeDirect(heating21());

        assertEquals(ProposalStatus.FAILED, p.getStatus());
        assertTrue(p.getError().contains("Hub weg"));
        verify(notifier).sendInfo(contains("fehlgeschlagen: Hub weg"));
    }

    @Test
    void executeDirectWithoutReasonOrMessageNeverWritesNull() {
        AgentAction noReason = new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 21.0), null, false);
        when(executor.execute(any())).thenThrow(new NullPointerException());

        AgentProposal p = service().executeDirect(noReason);

        assertEquals("(keine Begründung)", p.getReason());
        assertEquals("NullPointerException", p.getError());
        verify(notifier).sendInfo(contains("fehlgeschlagen: NullPointerException"));
    }

    @Test
    void executeDirectInfoUsesReasonFallback() {
        AgentAction noReason = new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 21.0), " ", false);
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "ok"));

        service().executeDirect(noReason);

        verify(notifier).sendInfo(argThat(text -> text.endsWith("(keine Begründung)") && !text.contains("null")));
    }

    // --- NOTIFY und Ruhezeit ---

    private AgentAction telegramMessage(boolean urgent) {
        return new AgentAction(ActionClass.NOTIFY, "telegram",
                Map.of("channel", "telegram", "message", "Fenster offen"), "Regen", urgent);
    }

    private AgentProposal heldMessage(long id) {
        return AgentProposal.builder().id(id).actionClass(ActionClass.NOTIFY).target("telegram")
                .actionJson(AgentActionJson.write(objectMapper, telegramMessage(false))).reason("Regen")
                .status(ProposalStatus.PENDING).createdAt(NOW.minusHours(6)).build();
    }

    @Test
    void rejectedNotificationNoteOmitsTheModelText() {
        AgentProposal held = heldMessage(9L);
        held.setDeliveredAt(NOW.minusMinutes(5));
        when(repository.findById(9L)).thenReturn(Optional.of(held));
        when(repository.claim(eq(9L), eq(ProposalStatus.PENDING), eq(ProposalStatus.REJECTED), any(), any())).thenReturn(1);

        service().decide(9L, ProposalDecision.REJECT, "Benedikt");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(noteService).create(eq(NoteKind.OBSERVATION), text.capture(), eq(NoteSource.USER), any());
        assertEquals("Benedikt hat abgelehnt: Nachricht", text.getValue());
        assertFalse(text.getValue().contains("Fenster offen"));
    }

    @Test
    void rememberingANotificationIsRefusedBeforeAnyClaim() {
        AgentProposal held = heldMessage(9L);
        held.setDeliveredAt(NOW.minusMinutes(5));
        when(repository.findById(9L)).thenReturn(Optional.of(held));

        assertThrows(IllegalArgumentException.class,
                () -> service().decide(9L, ProposalDecision.APPROVE_AND_REMEMBER, "Benedikt"));

        verify(repository, never()).claim(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(noteService, executor);
    }

    @Test
    void executeDirectNotifySendsNoExtraInfo() {
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "Nachricht gesendet."));

        AgentProposal p = service().executeDirect(telegramMessage(false));

        assertEquals(ProposalStatus.EXECUTED, p.getStatus());
        verify(executor).execute(any());
        verify(notifier, never()).sendInfo(any());
    }

    @Test
    void nonUrgentNotificationIsHeldDuringQuietHoursAndSentInTheMorning() {
        when(quietHours.isQuietNow()).thenReturn(true);

        AgentProposal held = service().executeDirect(telegramMessage(false));

        assertEquals(ProposalStatus.PENDING, held.getStatus());
        assertNull(held.getDeliveredAt());
        assertNull(held.getDecidedBy());
        verify(executor, never()).execute(any());
        verifyNoInteractions(notifier);

        held.setId(7L);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(held));
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "Nachricht gesendet."));

        assertEquals(1, service().deliverPending());

        InOrder order = inOrder(repository, executor);
        order.verify(repository).claim(7L, ProposalStatus.PENDING, ProposalStatus.EXECUTED, "system", NOW);
        order.verify(executor).execute(telegramMessage(false));
        assertEquals(ProposalStatus.EXECUTED, held.getStatus());
        verify(executor, never()).isSatisfied(any());
        verify(notifier, never()).sendProposal(any());
        verify(notifier, never()).sendInfo(any());
    }

    @Test
    void urgentNotificationGoesOutImmediatelyDuringQuietHours() {
        when(quietHours.isQuietNow()).thenReturn(true);
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "Nachricht gesendet."));

        AgentProposal p = service().executeDirect(telegramMessage(true));

        assertEquals(ProposalStatus.EXECUTED, p.getStatus());
        verify(executor).execute(telegramMessage(true));
    }

    @Test
    void heldNotificationIsNotSentWhenClaimLost() {
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(heldMessage(7L)));
        when(repository.claim(anyLong(), any(), any(), any(), any())).thenReturn(0);

        assertEquals(0, service().deliverPending());

        verify(executor, never()).execute(any());
    }

    @Test
    void heldNotificationFailureIsSavedAsFailed() {
        AgentProposal held = heldMessage(7L);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(repository.findByStatus(ProposalStatus.PENDING)).thenReturn(List.of(held));
        when(executor.execute(any())).thenThrow(new RuntimeException("Telegram weg"));

        assertEquals(1, assertDoesNotThrow(() -> service().deliverPending()));

        assertEquals(ProposalStatus.FAILED, held.getStatus());
        assertEquals("Telegram weg", held.getError());
        verify(repository).save(held);
        verify(executor, times(1)).execute(any());
    }

    @Test
    void infoMessagesAreSuppressedDuringQuietHours() {
        when(quietHours.isQuietNow()).thenReturn(true);
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "ok"));

        AgentProposal p = service().executeDirect(heating21());

        assertEquals(ProposalStatus.EXECUTED, p.getStatus());
        verify(repository).save(p);
        verify(notifier, never()).sendInfo(any());
    }

    @Test
    void urgentActionStillInformsDuringQuietHours() {
        when(quietHours.isQuietNow()).thenReturn(true);
        when(executor.execute(any())).thenReturn(new ActionOutcome(false, "ok"));

        service().executeDirect(heating21(true));

        verify(notifier).sendInfo(contains("Heizung 3 auf 21.0 °C"));
    }

    @Test
    void decideFailureWithoutMessageNamesExceptionType() {
        AgentProposal p = pending(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        when(executor.execute(any())).thenThrow(new NullPointerException());

        service().decide(1L, ProposalDecision.APPROVE, "Benedikt");

        assertEquals("NullPointerException", p.getError());
        verify(notifier).markDecided(p, "Fehlgeschlagen: NullPointerException");
    }

    // --- Lesen ---

    @SuppressWarnings("unchecked")
    @Test
    void recentForPromptAsksForAllStatusesOfTheLastDay() {
        AgentProposal p = pending(1L);
        when(repository.findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(anyCollection(), any()))
                .thenReturn(List.of(p));

        assertEquals(List.of(p), service().recentForPrompt());

        ArgumentCaptor<Collection<ProposalStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findByStatusInAndCreatedAtAfterOrderByCreatedAtDesc(statuses.capture(), eq(NOW.minusHours(24)));
        assertEquals(EnumSet.allOf(ProposalStatus.class), EnumSet.copyOf(statuses.getValue()));
    }

    @Test
    void countPendingAndListRecentDelegate() {
        when(repository.countByStatus(ProposalStatus.PENDING)).thenReturn(2L);
        when(repository.findTop50ByOrderByCreatedAtDesc()).thenReturn(List.of(pending(1L)));

        assertEquals(2L, service().countPending());
        assertEquals(1, service().listRecent().size());
    }

    @Test
    void actionOfRestoresTheAction() {
        assertEquals(heating21(), service().actionOf(pending(1L)));
    }

    // --- parseCallback ---

    @Test
    void parseCallbackHandlesGarbage() {
        Optional<AgentProposalService.ParsedCallback> parsed = AgentProposalService.parseCallback("agent:7:APPROVE");
        assertTrue(parsed.isPresent());
        assertEquals(7L, parsed.get().proposalId());
        assertEquals(ProposalDecision.APPROVE, parsed.get().decision());
        assertEquals(ProposalDecision.APPROVE_AND_REMEMBER,
                AgentProposalService.parseCallback("agent:7:APPROVE_AND_REMEMBER").orElseThrow().decision());
        assertTrue(AgentProposalService.parseCallback("agent:x:APPROVE").isEmpty());
        assertTrue(AgentProposalService.parseCallback("agent:7:MAYBE").isEmpty());
        assertTrue(AgentProposalService.parseCallback("other:7:APPROVE").isEmpty());
        assertTrue(AgentProposalService.parseCallback("agent:7:APPROVE:extra").isEmpty());
        assertTrue(AgentProposalService.parseCallback("foo").isEmpty());
        assertTrue(AgentProposalService.parseCallback(null).isEmpty());
    }

    @Test
    void callbackDataRoundTrips() {
        assertEquals("agent:7:REJECT", AgentProposalService.callbackData(7L, ProposalDecision.REJECT));
        assertEquals(ProposalDecision.REJECT,
                AgentProposalService.parseCallback(AgentProposalService.callbackData(7L, ProposalDecision.REJECT))
                        .orElseThrow().decision());
    }
}
