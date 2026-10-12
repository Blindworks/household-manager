package com.household.manager.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.AgentProposal;
import com.household.manager.model.entity.AgentProposalMessage;
import com.household.manager.repository.AgentProposalMessageRepository;
import com.household.manager.telegram.InlineButton;
import com.household.manager.telegram.TelegramApiClient;
import com.household.manager.telegram.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Telegram-Seite der Vorschläge: Nachricht mit Knöpfen senden, nach der Entscheidung die Knöpfe
 * wieder entfernen, Info-Zeilen verschicken.
 *
 * <p>Keine Methode wirft. Telegram ist nur ein Zustellweg — ein Vorschlag existiert unabhängig davon
 * in der DB und ist auf der Admin-Seite entscheidbar. Ein Zustellfehler darf deshalb weder die
 * Entscheidung noch die Agentenschleife kippen. Fehler sind je Chat bzw. je Nachricht isoliert,
 * damit ein gesperrter Chat die übrigen Haushaltsmitglieder nicht von der Frage abschneidet.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentNotifier {

    static final String LABEL_YES = "Ja";
    static final String LABEL_NO = "Nein";
    static final String LABEL_REMEMBER = "Ja, und merken";

    private final TelegramProperties telegramProperties;
    private final TelegramApiClient apiClient;
    private final AgentProposalMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public boolean isConfigured() {
        return telegramProperties.isConfigured();
    }

    /**
     * Sendet den Vorschlag an alle erlaubten Chats und merkt sich je Chat die Nachrichten-Id —
     * nur so lassen sich nach der Entscheidung die Knöpfe überall entfernen, auch in dem Chat,
     * der nicht geantwortet hat (sonst könnte dort ein zweiter Druck eine schon entschiedene Frage treffen).
     */
    public void sendProposal(AgentProposal p) {
        if (!isConfigured()) {
            log.debug("Telegram nicht konfiguriert — Vorschlag {} nur in der Admin-Seite", p.getId());
            return;
        }
        String text;
        try {
            text = "Vorschlag #" + p.getId() + "\n" + p.getReason() + "\n\n→ " + describe(p) + "?";
        } catch (Exception ex) {
            log.warn("Vorschlag {} nicht darstellbar: {}", p.getId(), ex.getMessage());
            return;
        }
        List<List<InlineButton>> keyboard = List.of(List.of(
                new InlineButton(LABEL_YES, AgentProposalService.callbackData(p.getId(), ProposalDecision.APPROVE)),
                new InlineButton(LABEL_NO, AgentProposalService.callbackData(p.getId(), ProposalDecision.REJECT)),
                new InlineButton(LABEL_REMEMBER,
                        AgentProposalService.callbackData(p.getId(), ProposalDecision.APPROVE_AND_REMEMBER))));
        for (long chatId : telegramProperties.getAllowedChatIds()) {
            try {
                long messageId = apiClient.sendMessage(chatId, text, keyboard);
                messageRepository.save(AgentProposalMessage.builder()
                        .proposalId(p.getId()).chatId(chatId).messageId(messageId).build());
            } catch (Exception ex) {
                log.warn("Vorschlag {} an Chat {} nicht zustellbar: {}", p.getId(), chatId, ex.getMessage());
            }
        }
    }

    /** Entfernt die Knöpfe in allen zum Vorschlag gesendeten Nachrichten und hängt "→ label" an. */
    public void markDecided(AgentProposal p, String label) {
        if (!isConfigured()) {
            return;
        }
        List<AgentProposalMessage> messages;
        try {
            messages = messageRepository.findByProposalId(p.getId());
        } catch (Exception ex) {
            log.warn("Nachrichten zu Vorschlag {} nicht lesbar: {}", p.getId(), ex.getMessage());
            return;
        }
        for (AgentProposalMessage message : messages) {
            try {
                apiClient.editReplyMarkup(message.getChatId(), message.getMessageId(), List.of());
                apiClient.sendMessage(message.getChatId(), "→ " + label);
            } catch (Exception ex) {
                log.warn("Vorschlag {} in Chat {} nicht aktualisierbar: {}", p.getId(), message.getChatId(),
                        ex.getMessage());
            }
        }
    }

    /** Reine Mitteilung ohne Knöpfe an alle erlaubten Chats. */
    public void sendInfo(String text) {
        if (!isConfigured()) {
            return;
        }
        for (long chatId : telegramProperties.getAllowedChatIds()) {
            try {
                apiClient.sendMessage(chatId, text);
            } catch (Exception ex) {
                log.warn("Hausverstand-Info an Chat {} nicht zustellbar: {}", chatId, ex.getMessage());
            }
        }
    }

    private String describe(AgentProposal p) {
        return AgentActionJson.read(objectMapper, p.getActionJson()).describe();
    }
}
