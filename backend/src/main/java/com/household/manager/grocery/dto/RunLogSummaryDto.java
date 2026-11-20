package com.household.manager.grocery.dto;

import com.household.manager.grocery.entity.GroceryRunLog;

import java.time.LocalDateTime;

/** Kennzahlen eines protokollierten Laufs (Liste auf der Admin-Seite). */
public record RunLogSummaryDto(long id, GroceryRunLog.Trigger trigger, LocalDateTime startedAt,
                               LocalDateTime finishedAt, boolean ok, String error, int offers, int rawOffers,
                               int products, int aiCalls, int estimateCalls, int inputTokens, int outputTokens,
                               int aiFailures, int deferred, int newJudgements, int deals, int sourceFailures) {

    public static RunLogSummaryDto of(GroceryRunLog log) {
        return new RunLogSummaryDto(log.getId(), log.getTrigger(), log.getStartedAt(), log.getFinishedAt(),
                log.isOk(), log.getError(), log.getOffers(), log.getRawOffers(), log.getProducts(), log.getAiCalls(),
                log.getEstimateCalls(), log.getInputTokens(), log.getOutputTokens(), log.getAiFailures(),
                log.getDeferred(), log.getNewJudgements(), log.getDeals(), log.getSourceFailures());
    }
}
