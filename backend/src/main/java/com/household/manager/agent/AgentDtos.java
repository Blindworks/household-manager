package com.household.manager.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ein- und Ausgabeformen der Hausverstand-API. Bewusst eigene Records statt der Entities:
 * {@code AgentProposal} traegt das rohe Aktions-JSON, das nicht nach aussen gehoeren soll — die
 * Oberflaeche bekommt stattdessen die lesbare {@code description}.
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** Das Wandtablet liest nur dies (KIOSK); {@code telegramConfigured} steuert den Hinweis auf der Admin-Seite. */
    public record SummaryResponse(long pendingProposals, boolean active, boolean telegramConfigured) {
    }

    public record SettingsDto(boolean active) {
    }

    public record PolicyDto(ActionClass actionClass, ActionPolicy policy) {
    }

    public record PolicyUpdateRequest(@NotNull ActionPolicy policy) {
    }

    public record NoteDto(Long id, NoteKind kind, String text, NoteSource source,
                          LocalDateTime createdAt, LocalDateTime expiresAt) {
    }

    public record NoteRequest(@NotNull NoteKind kind, @NotBlank @Size(max = 500) String text,
                              LocalDateTime expiresAt) {
    }

    public record ProposalDto(Long id, ActionClass actionClass, String target, String description,
                              String reason, boolean urgent, ProposalStatus status, String decidedBy,
                              LocalDateTime decidedAt, LocalDateTime deliveredAt, String error,
                              LocalDateTime createdAt) {
    }

    public record DecisionRequest(@NotNull ProposalDecision decision) {
    }

    public record RunDto(Long id, AgentTriggerType triggerType, String triggerDetail, LocalDateTime startedAt,
                         long durationMs, String model, int inputTokens, int outputTokens, int toolCalls,
                         String summary, String error) {
    }

    public record RunsResponse(List<RunDto> runs, long inputTokensToday, long outputTokensToday) {
    }
}
