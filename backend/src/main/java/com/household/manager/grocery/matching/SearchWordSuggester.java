package com.household.manager.grocery.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.ai.AnthropicApiClient;
import com.household.manager.ai.AnthropicMessage;
import com.household.manager.ai.AnthropicRequest;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BaseUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Schlägt Suchwörter und Grundeinheit für ein Produkt vor; ohne Claude über einen einfachen Rückfall. */
@Slf4j
@Component
public class SearchWordSuggester {

    public record Suggestion(List<String> searchWords, BaseUnit baseUnit) {
    }

    static final int MAX_SEARCH_WORDS = 8;
    private static final Set<String> FILLER = Set.of("dose", "dosen", "flasche", "flaschen", "fl", "packung",
            "pack", "stück", "stueck", "stk", "je", "liter", "ml", "kg", "und", "oder", "mit", "ohne", "von", "der",
            "die", "das", "gramm", "kilo", "cl");
    private static final Pattern LITER = Pattern.compile("(?<![\\p{L}])(l|ml|cl|liter)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KILOGRAM = Pattern.compile("(?<![\\p{L}])(g|kg|gramm|kilo)(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCE = Pattern.compile("^```[a-zA-Z]*\\s*|\\s*```$");
    private static final String SYSTEM = "Du hilfst bei der Suche nach Supermarkt-Angeboten. Zu einem Produktnamen "
            + "lieferst du kurze Suchwörter (kleingeschrieben, 2 bis 6 Stück, Marke und Produktart, keine Mengen), "
            + "mit denen sich das Produkt in Angebotstiteln finden lässt, und die Grundeinheit, in der der Preis "
            + "verglichen wird. Antworte ausschließlich mit JSON: "
            + "{\"searchWords\":[...],\"baseUnit\":\"LITER|KILOGRAM|PIECE\"}";

    private final AnthropicApiClient client;
    private final GroceryProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearchWordSuggester(AnthropicApiClient client, GroceryProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public Suggestion suggest(String productName) {
        try {
            AnthropicRequest request = AnthropicRequest.of(properties.getAiModel(), 300, SYSTEM,
                    List.of(AnthropicMessage.user("Produkt: " + productName)), null);
            Suggestion parsed = parse(client.createMessage(request).text());
            if (parsed != null) {
                return parsed;
            }
            log.warn("Suchwort-Vorschlag von Claude unbrauchbar, nutze Rückfall für '{}'", productName);
        } catch (RuntimeException ex) {
            log.warn("Suchwort-Vorschlag über Claude nicht möglich ({}), nutze Rückfall", ex.getMessage());
        }
        return fallback(productName);
    }

    private Suggestion parse(String text) {
        try {
            JsonNode root = objectMapper.readTree(FENCE.matcher(text.trim()).replaceAll("").trim());
            LinkedHashSet<String> words = new LinkedHashSet<>();
            for (JsonNode node : root.path("searchWords")) {
                String word = node.asText("").replace(',', ' ').trim().replaceAll("\\s+", " ")
                        .toLowerCase(Locale.ROOT);
                if (!word.isEmpty() && words.size() < MAX_SEARCH_WORDS) {
                    words.add(word);
                }
            }
            BaseUnit unit = BaseUnit.valueOf(root.path("baseUnit").asText("").trim().toUpperCase(Locale.ROOT));
            return words.isEmpty() ? null : new Suggestion(List.copyOf(words), unit);
        } catch (Exception ex) {
            return null;
        }
    }

    static Suggestion fallback(String productName) {
        String name = productName == null ? "" : productName;
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (String raw : name.toLowerCase(Locale.ROOT).split("\\s+")) {
            String word = raw.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
            boolean hasDigit = raw.chars().anyMatch(Character::isDigit);
            if (word.length() >= 3 && !hasDigit && !FILLER.contains(word) && words.size() < MAX_SEARCH_WORDS) {
                words.add(word);
            }
        }
        if (words.isEmpty()) {
            for (String token : ProductWords.tokens(name)) {
                if (token.length() >= 2 && token.chars().anyMatch(Character::isLetter)
                        && words.size() < MAX_SEARCH_WORDS) {
                    words.add(token);
                }
            }
        }
        BaseUnit unit = LITER.matcher(name).find() ? BaseUnit.LITER
                : KILOGRAM.matcher(name).find() ? BaseUnit.KILOGRAM : BaseUnit.PIECE;
        return new Suggestion(List.copyOf(words), unit);
    }
}
