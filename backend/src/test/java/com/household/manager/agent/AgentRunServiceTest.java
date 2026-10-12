package com.household.manager.agent;

import com.household.manager.ai.*;
import com.household.manager.audit.AuditActor;
import com.household.manager.audit.AuditActorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentRunServiceTest {

    private AgentProperties properties;
    private AnthropicApiClient client;
    private AgentToolRegistry registry;
    private HouseSnapshotBuilder snapshotBuilder;
    private AgentRunRecorder recorder;
    private AgentFailureStreak failureStreak;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.setMaxToolIterations(3);
        properties.setRunTimeoutSeconds(90);
        client = mock(AnthropicApiClient.class);
        registry = mock(AgentToolRegistry.class);
        snapshotBuilder = mock(HouseSnapshotBuilder.class);
        recorder = mock(AgentRunRecorder.class);
        failureStreak = mock(AgentFailureStreak.class);
        when(snapshotBuilder.build()).thenReturn("SNAP");
        when(registry.toolDefinitions(AgentScope.HOUSE)).thenReturn(List.of(Map.of("name", "notify")));
    }

    @AfterEach
    void tearDown() {
        AuditActorContext.clear();
    }

    private AgentRunService service() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), ZoneId.of("Europe/Berlin"));
        return new AgentRunService(properties, client, registry, snapshotBuilder, recorder, failureStreak, clock);
    }

    private static AgentTrigger scheduled() {
        return new AgentTrigger(AgentTriggerType.SCHEDULED, List.of());
    }

    private static AnthropicResponse endTurn(String text, int in, int out) {
        return new AnthropicResponse("end_turn", List.of(Map.of("type", "text", "text", text)), in, out);
    }

    private static AnthropicResponse toolUse(String id) {
        return new AnthropicResponse("tool_use",
                List.of(Map.of("type", "tool_use", "id", id, "name", "notify", "input", Map.of("message", "hi"))), 10, 5);
    }

    @Test
    void plainAnswerRecordsSummaryWithoutToolCalls() {
        when(client.createMessage(any())).thenReturn(endTurn("Nichts zu tun.", 500, 20));

        AgentRunResult r = service().run(scheduled());

        assertTrue(r.success());
        assertEquals("Nichts zu tun.", r.summary());
        assertEquals(500, r.inputTokens());
        assertEquals(20, r.outputTokens());
        assertEquals(0, r.toolCalls());
        verify(recorder).record(any(), eq(r), eq("claude-sonnet-4-5"), any());
        verify(failureStreak).onResult(r);
    }

    @Test
    void systemPromptContainsCachedRulesAndSnapshot() {
        when(client.createMessage(any())).thenReturn(endTurn("ok", 1, 1));

        service().run(scheduled());

        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        verify(client).createMessage(captor.capture());
        AnthropicRequest request = captor.getValue();
        assertTrue(request.system().get(0).containsKey("cache_control"));
        assertEquals(AgentRunService.STATIC_RULES, request.system().get(0).get("text"));
        assertTrue(String.valueOf(request.system().get(1).get("text")).contains("SNAP"));
        assertFalse(request.system().get(1).containsKey("cache_control"));
        assertEquals(registry.toolDefinitions(AgentScope.HOUSE), request.tools());
        String userText = String.valueOf(request.messages().get(0).content().get(0).get("text"));
        assertEquals("Grundtakt\n\n" + AgentRunService.TASK_INSTRUCTION, userText);
    }

    @Test
    void toolCallsAreExecutedInHouseScopeAndFedBack() {
        when(client.createMessage(any())).thenReturn(toolUse("t1"), endTurn("fertig", 1, 1));
        when(registry.execute(eq(AgentScope.HOUSE), eq("notify"), any())).thenReturn(ToolResult.ok("gesendet"));

        AgentRunResult r = service().run(scheduled());

        assertTrue(r.success());
        assertEquals(1, r.toolCalls());
        assertEquals(11, r.inputTokens());
        verify(registry).execute(eq(AgentScope.HOUSE), eq("notify"), eq(Map.of("message", "hi")));
        ArgumentCaptor<AnthropicRequest> captor = ArgumentCaptor.forClass(AnthropicRequest.class);
        verify(client, times(2)).createMessage(captor.capture());
        // Mockito haelt dieselbe (mutierte) Nachrichtenliste; die letzte Nachricht ist das tool_result.
        List<AnthropicMessage> messages = captor.getAllValues().get(1).messages();
        Map<String, Object> block = messages.get(messages.size() - 1).content().get(0);
        assertEquals("tool_result", block.get("type"));
        assertEquals("t1", block.get("tool_use_id"));
        assertEquals("gesendet", block.get("content"));
    }

    @Test
    void iterationCapEndsRunAsFailure() {
        when(client.createMessage(any())).thenReturn(toolUse("t"));
        when(registry.execute(any(), any(), any())).thenReturn(ToolResult.ok("x"));

        AgentRunResult r = service().run(scheduled());

        assertFalse(r.success());
        assertTrue(r.error().contains("Iterationen"));
        assertEquals(3, r.toolCalls());
        verify(client, times(3)).createMessage(any());
    }

    @Test
    void exceededTimeBudgetEndsRunAsFailureBeforeCallingApi() {
        properties.setRunTimeoutSeconds(-1);

        AgentRunResult r = service().run(scheduled());

        assertFalse(r.success());
        assertTrue(r.error().contains("Zeitbudget"));
        verifyNoInteractions(client);
    }

    @Test
    void apiFailureIsRecordedNotThrown() {
        when(client.createMessage(any())).thenThrow(new AiException("down", null));

        AgentRunResult r = service().run(scheduled());

        assertFalse(r.success());
        assertTrue(r.error().contains("down"));
        verify(recorder).record(any(), any(), any(), any());
        verify(failureStreak).onResult(r);
    }

    @Test
    void snapshotFailureIsRecordedNotThrown() {
        when(snapshotBuilder.build()).thenThrow(new RuntimeException("db"));

        AgentRunResult r = service().run(scheduled());

        assertFalse(r.success());
        verify(recorder, times(1)).record(any(), eq(r), any(), any());
    }

    @Test
    void failureWithoutMessageStillHasAnError() {
        when(client.createMessage(any())).thenThrow(new NullPointerException());

        AgentRunResult r = service().run(scheduled());

        assertFalse(r.success());
        assertNotNull(r.error());
    }

    @Test
    void auditActorIsAgentDuringRunAndClearedAfter() {
        when(client.createMessage(any())).thenAnswer(inv -> {
            assertEquals(AuditActor.agent(), AuditActorContext.get());
            return endTurn("ok", 1, 1);
        });

        service().run(scheduled());

        assertNull(AuditActorContext.get());
    }

    @Test
    void runSurvivesThrowingRecorder() {
        when(client.createMessage(any())).thenReturn(endTurn("ok", 1, 1));
        doThrow(new RuntimeException("db")).when(recorder).record(any(), any(), any(), any());

        AgentRunResult r = assertDoesNotThrow(() -> service().run(scheduled()));

        assertTrue(r.success());
        verify(failureStreak).onResult(r);
    }

    @Test
    void runSurvivesThrowingFailureStreak() {
        when(client.createMessage(any())).thenReturn(endTurn("ok", 1, 1));
        doThrow(new RuntimeException("telegram")).when(failureStreak).onResult(any());

        AgentRunResult r = assertDoesNotThrow(() -> service().run(scheduled()));

        assertTrue(r.success());
        verify(recorder).record(any(), eq(r), any(), any());
    }

    @Test
    void restoresPreviousAuditActor() {
        AuditActor admin = AuditActor.user("admin");
        AuditActorContext.set(admin);
        when(client.createMessage(any())).thenReturn(endTurn("ok", 1, 1));

        service().run(scheduled());

        assertEquals(admin, AuditActorContext.get());
    }

    @Test
    void staticRulesContainTheBindingRules() {
        String rules = AgentRunService.STATIC_RULES;
        assertTrue(rules.contains("Hausverstand"));
        assertTrue(rules.contains("urgent"));
        assertTrue(rules.contains("expiresInHours"));
        assertTrue(rules.contains("24 Stunden"));
        assertTrue(rules.contains("USER"));
    }
}
