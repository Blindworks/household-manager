package com.household.manager.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.model.entity.AgentProposalMessage;
import com.household.manager.repository.AgentProposalMessageRepository;
import com.household.manager.telegram.InlineButton;
import com.household.manager.telegram.TelegramApiClient;
import com.household.manager.telegram.TelegramException;
import com.household.manager.telegram.TelegramProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentNotifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TelegramProperties properties;
    private TelegramApiClient apiClient;
    private AgentProposalMessageRepository messageRepository;

    @BeforeEach
    void setUp() {
        properties = new TelegramProperties();
        apiClient = mock(TelegramApiClient.class);
        messageRepository = mock(AgentProposalMessageRepository.class);
    }

    private void configure(Long... chats) {
        properties.setBotToken("123:SECRET");
        properties.setAllowedChatIds(List.of(chats));
    }

    private AgentNotifier notifier() {
        return new AgentNotifier(properties, apiClient, messageRepository, objectMapper);
    }

    private AgentProposal proposal() {
        AgentAction action = new AgentAction(ActionClass.HEATING, "3", Map.of("targetTemp", 21.0),
                "Wohnzimmer ist kalt", false);
        return AgentProposal.builder().id(7L).actionClass(ActionClass.HEATING).target("3")
                .actionJson(AgentActionJson.write(objectMapper, action)).reason("Wohnzimmer ist kalt")
                .status(ProposalStatus.PENDING).createdAt(LocalDateTime.of(2026, 10, 12, 12, 0)).build();
    }

    @Test
    void notConfiguredSendsAndStoresNothing() {
        assertFalse(notifier().isConfigured());

        assertDoesNotThrow(() -> notifier().sendProposal(proposal()));

        verifyNoInteractions(apiClient, messageRepository);
    }

    @SuppressWarnings("unchecked")
    @Test
    void sendProposalSendsKeyboardAndStoresMessagePerChat() {
        configure(1L);
        when(apiClient.sendMessage(eq(1L), anyString(), anyList())).thenReturn(55L);

        notifier().sendProposal(proposal());

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<List<InlineButton>>> keyboard = ArgumentCaptor.forClass(List.class);
        verify(apiClient).sendMessage(eq(1L), text.capture(), keyboard.capture());
        assertTrue(text.getValue().contains("Vorschlag #7"));
        assertTrue(text.getValue().contains("Wohnzimmer ist kalt"));
        assertTrue(text.getValue().contains("Heizung 3 auf 21.0 °C"));
        assertEquals(List.of(
                new InlineButton("Ja", "agent:7:APPROVE"),
                new InlineButton("Nein", "agent:7:REJECT"),
                new InlineButton("Ja, und merken", "agent:7:APPROVE_AND_REMEMBER")), keyboard.getValue().get(0));

        ArgumentCaptor<AgentProposalMessage> saved = ArgumentCaptor.forClass(AgentProposalMessage.class);
        verify(messageRepository).save(saved.capture());
        assertEquals(7L, saved.getValue().getProposalId());
        assertEquals(1L, saved.getValue().getChatId());
        assertEquals(55L, saved.getValue().getMessageId());
    }

    @Test
    void failingChatDoesNotBlockTheOther() {
        configure(1L, 2L);
        when(apiClient.sendMessage(eq(1L), anyString(), anyList())).thenThrow(new TelegramException("weg", null));
        when(apiClient.sendMessage(eq(2L), anyString(), anyList())).thenReturn(66L);

        assertDoesNotThrow(() -> notifier().sendProposal(proposal()));

        ArgumentCaptor<AgentProposalMessage> saved = ArgumentCaptor.forClass(AgentProposalMessage.class);
        verify(messageRepository, times(1)).save(saved.capture());
        assertEquals(2L, saved.getValue().getChatId());
        assertEquals(66L, saved.getValue().getMessageId());
    }

    @Test
    void markDecidedRemovesButtonsAndSendsFollowUp() {
        configure(1L, 2L);
        when(messageRepository.findByProposalId(7L)).thenReturn(List.of(
                AgentProposalMessage.builder().proposalId(7L).chatId(1L).messageId(10L).build(),
                AgentProposalMessage.builder().proposalId(7L).chatId(2L).messageId(20L).build()));

        notifier().markDecided(proposal(), "Ausgeführt von Benedikt");

        verify(apiClient).editReplyMarkup(1L, 10L, List.of());
        verify(apiClient).editReplyMarkup(2L, 20L, List.of());
        verify(apiClient).sendMessage(1L, "→ Ausgeführt von Benedikt");
        verify(apiClient).sendMessage(2L, "→ Ausgeführt von Benedikt");
    }

    @Test
    void markDecidedIsolatesFailuresPerMessage() {
        configure(1L, 2L);
        when(messageRepository.findByProposalId(7L)).thenReturn(List.of(
                AgentProposalMessage.builder().proposalId(7L).chatId(1L).messageId(10L).build(),
                AgentProposalMessage.builder().proposalId(7L).chatId(2L).messageId(20L).build()));
        doThrow(new TelegramException("weg", null)).when(apiClient).editReplyMarkup(1L, 10L, List.of());

        assertDoesNotThrow(() -> notifier().markDecided(proposal(), "Abgelehnt"));

        verify(apiClient).editReplyMarkup(2L, 20L, List.of());
        verify(apiClient).sendMessage(2L, "→ Abgelehnt");
    }

    @Test
    void markDecidedNeverThrowsOnRepositoryFailure() {
        configure(1L);
        when(messageRepository.findByProposalId(anyLong())).thenThrow(new RuntimeException("DB weg"));

        assertDoesNotThrow(() -> notifier().markDecided(proposal(), "Verfallen"));
    }

    @Test
    void sendInfoGoesToAllChatsAndNeverThrows() {
        configure(1L, 2L);
        doThrow(new TelegramException("weg", null)).when(apiClient).sendMessage(1L, "Info");

        assertDoesNotThrow(() -> notifier().sendInfo("Info"));

        verify(apiClient).sendMessage(2L, "Info");
    }

    @Test
    void sendInfoWithoutConfigurationDoesNothing() {
        notifier().sendInfo("Info");

        verifyNoInteractions(apiClient);
    }
}
