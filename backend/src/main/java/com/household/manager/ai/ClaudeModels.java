package com.household.manager.ai;

import java.util.List;
import java.util.Optional;

/**
 * Bekannte Claude-Modelle mit Listenpreisen (US-Dollar je Million Tokens, Cache 5 Minuten; Stand 2026-11-25,
 * https://platform.claude.com/docs/en/about-claude/pricing). Preise aendern sich nicht von selbst: hier nachziehen.
 */
public final class ClaudeModels {

    /**
     * @param effort Denkaufwand fuer Modelle, die von sich aus nachdenken; null, wenn das Modell die Angabe ablehnt
     */
    public record Model(String id, String label, double inputPrice, double cacheWritePrice, double cacheReadPrice,
                        double outputPrice, String effort) {

        public double cost(long input, long output, long cacheRead, long cacheWrite) {
            return (input * inputPrice + output * outputPrice + cacheRead * cacheReadPrice
                    + cacheWrite * cacheWritePrice) / 1_000_000;
        }
    }

    /** Guenstigstes zuerst. Haiku 5.5: Preis fuer Prompts bis 100 000 Tokens. */
    public static final List<Model> ALL = List.of(
            new Model("claude-haiku-5-5", "Haiku 5.5", 0.10, 0.125, 0.01, 0.50, "low"),
            new Model("claude-haiku-4-5", "Haiku 4.5", 1, 1.25, 0.10, 5, null),
            new Model("claude-sonnet-5-5", "Sonnet 5.5", 2, 2.50, 0.10, 10, "low"),
            new Model("claude-sonnet-4-5", "Sonnet 4.5", 3, 3.75, 0.30, 15, null),
            new Model("claude-opus-5-5", "Opus 5.5", 4, 5, 0.20, 20, "low"));

    private ClaudeModels() {
    }

    /** Kennt auch datierte Ids wie {@code claude-haiku-4-5-20251001}. */
    public static Optional<Model> find(String id) {
        return id == null ? Optional.empty() : ALL.stream()
                .filter(model -> id.equals(model.id()) || id.startsWith(model.id() + "-20")).findFirst();
    }
}
