package com.household.manager.telegram;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import com.household.manager.systemlog.JobState;
import com.household.manager.systemlog.SystemJobMonitor;
import com.household.manager.agent.AgentProposalService;
import com.household.manager.agent.ProposalDecision;
import com.household.manager.agent.ProposalStatus;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.ai.AiProperties;
import com.household.manager.telegram.dto.TelegramCallbackQuery;
import com.household.manager.telegram.dto.TelegramUser;
import com.household.manager.telegram.dto.TelegramChat;
import com.household.manager.telegram.dto.TelegramMessage;
import com.household.manager.telegram.dto.TelegramUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramPollingServiceTest {

    @Mock
    private SystemJobMonitor jobMonitor;
    @Mock
    private TelegramApiClient apiClient;
    @Mock
    private TelegramAgentService agentService;
    @Mock
    private AgentProposalService proposalService;

    private TelegramPollingService service() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("t");
        AiProperties ai = new AiProperties();
        ai.setAnthropicApiKey("k");
        props.setAllowedChatIds(List.of(42L));
        return new TelegramPollingService(props, ai, apiClient, agentService, proposalService, jobMonitor);
    }

    private TelegramUpdate update(long chatId, String text) {
        return new TelegramUpdate(1, new TelegramMessage(new TelegramChat(chatId), text, null), null);
    }

    @Test
    void allowedChatGetsAnAgentReply() {
        when(agentService.handleUserMessage(42L, "hallo")).thenReturn("Hi!");

        service().handleUpdate(update(42L, "hallo"));

        verify(apiClient).sendMessage(42L, "Hi!");
    }

    @Test
    void foreignChatIsIgnoredCompletely() {
        service().handleUpdate(update(99L, "lass mich rein"));

        verifyNoInteractions(agentService);
        verify(apiClient, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void updatesWithoutTextAreIgnored() {
        service().handleUpdate(new TelegramUpdate(1, null, null));
        service().handleUpdate(update(42L, null));

        verifyNoInteractions(agentService);
    }

    @Test
    void sendFailureDoesNotPropagate() {
        when(agentService.handleUserMessage(42L, "hallo")).thenReturn("Hi!");
        doThrow(new TelegramException("weg", null)).when(apiClient).sendMessage(42L, "Hi!");

        assertDoesNotThrow(() -> service().handleUpdate(update(42L, "hallo")));
    }

    private TelegramUpdate callback(long chatId, String firstName, String data) {
        return new TelegramUpdate(1, null, new TelegramCallbackQuery("cb1", new TelegramUser(9L, firstName),
                new TelegramMessage(new TelegramChat(chatId), null, 42L), data));
    }

    @Test
    void callbackFromAllowedChatDecidesProposal() {
        service().handleUpdate(callback(42L, " Benedikt ", "agent:7:APPROVE"));

        verify(proposalService).decide(7L, ProposalDecision.APPROVE, "Benedikt");
        verify(apiClient).answerCallbackQuery("cb1", "Erledigt");
        verifyNoInteractions(agentService);
    }

    @Test
    void blankFirstNameFallsBackToChatId() {
        service().handleUpdate(callback(42L, " ", "agent:7:REJECT"));

        verify(proposalService).decide(7L, ProposalDecision.REJECT, "Chat 42");
    }

    @Test
    void callbackFromForeignChatIsIgnored() {
        service().handleUpdate(callback(999L, "Eve", "agent:7:APPROVE"));

        verifyNoInteractions(proposalService);
        verify(apiClient, never()).answerCallbackQuery(anyString(), anyString());
    }

    @Test
    void callbackWithoutMessageIsIgnored() {
        service().handleUpdate(new TelegramUpdate(1, null,
                new TelegramCallbackQuery("cb1", new TelegramUser(9L, "B"), null, "agent:7:APPROVE")));

        verifyNoInteractions(proposalService);
    }

    @Test
    void alreadyDecidedIsReportedToUser() {
        when(proposalService.decide(anyLong(), any(), any())).thenThrow(new IllegalStateException("bereits entschieden"));

        service().handleUpdate(callback(42L, "Benedikt", "agent:7:APPROVE"));

        verify(apiClient).answerCallbackQuery("cb1", "bereits entschieden");
    }

    @Test
    void failedActionIsAnsweredWithItsError() {
        AgentProposal failed = AgentProposal.builder().id(7L).status(ProposalStatus.FAILED).error("Hub offline").build();
        when(proposalService.decide(anyLong(), any(), any())).thenReturn(failed);

        service().handleUpdate(callback(42L, "Benedikt", "agent:7:APPROVE"));

        verify(apiClient).answerCallbackQuery("cb1", "Fehlgeschlagen: Hub offline");
    }

    @Test
    void longFailureTextIsShortenedFor190Chars() {
        AgentProposal failed = AgentProposal.builder().id(7L).status(ProposalStatus.FAILED).error("x".repeat(500)).build();
        when(proposalService.decide(anyLong(), any(), any())).thenReturn(failed);

        service().handleUpdate(callback(42L, "Benedikt", "agent:7:APPROVE"));

        verify(apiClient).answerCallbackQuery(eq("cb1"), argThat(text -> text.length() == 190
                && text.startsWith("Fehlgeschlagen: x") && text.endsWith("…")));
    }

    @Test
    void unexpectedFailureIsAnsweredGenerically() {
        when(proposalService.decide(anyLong(), any(), any())).thenThrow(new RuntimeException("db weg"));

        service().handleUpdate(callback(42L, "Benedikt", "agent:7:APPROVE"));

        verify(apiClient).answerCallbackQuery("cb1", "Fehler, siehe Admin-Seite");
    }

    @Test
    void garbageCallbackDataIsAnswered() {
        service().handleUpdate(callback(42L, "Benedikt", "foo"));

        verify(apiClient).answerCallbackQuery("cb1", "Unbekannter Knopf");
        verifyNoInteractions(proposalService);
    }

    @Test
    void answerFailureDoesNotPropagate() {
        doThrow(new TelegramException("weg", null)).when(apiClient).answerCallbackQuery("cb1", "Erledigt");

        assertDoesNotThrow(() -> service().handleUpdate(callback(42L, "Benedikt", "agent:7:APPROVE")));
    }

    @Test
    void notConfigured_recordsInactive() {
        TelegramPollingService unconfigured = new TelegramPollingService(
                new TelegramProperties(), new AiProperties(), apiClient, agentService, proposalService, jobMonitor);

        unconfigured.start();

        verify(jobMonitor).recordOutcome(eq(TelegramPollingService.JOB_KEY), eq(JobState.INACTIVE), any());
    }

    @Test
    void getUpdatesFails_recordsFailing() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("t");
        props.setAllowedChatIds(List.of(42L));
        props.setErrorBackoffMs(1);
        AiProperties ai = new AiProperties();
        ai.setAnthropicApiKey("k");
        when(apiClient.getUpdates(anyLong(), anyInt())).thenThrow(new RuntimeException("Telegram weg"));
        TelegramPollingService polling = new TelegramPollingService(
                props, ai, apiClient, agentService, proposalService, jobMonitor);

        polling.start();
        try {
            verify(jobMonitor, timeout(3000).atLeastOnce())
                    .recordOutcome(eq(TelegramPollingService.JOB_KEY), eq(JobState.FAILING), eq("Telegram weg"));
        } finally {
            polling.stop();
        }
    }

    @Test
    void getUpdatesSucceeds_recordsOk() {
        TelegramProperties props = new TelegramProperties();
        props.setBotToken("t");
        props.setAllowedChatIds(List.of(42L));
        AiProperties ai = new AiProperties();
        ai.setAnthropicApiKey("k");
        when(apiClient.getUpdates(anyLong(), anyInt())).thenReturn(List.of());
        TelegramPollingService polling = new TelegramPollingService(
                props, ai, apiClient, agentService, proposalService, jobMonitor);

        polling.start();
        try {
            verify(jobMonitor, timeout(3000).atLeastOnce())
                    .recordOutcome(TelegramPollingService.JOB_KEY, JobState.OK, null);
        } finally {
            polling.stop();
        }
    }
}
