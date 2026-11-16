package com.household.manager.grocery.matching;

import com.household.manager.ai.AiException;
import com.household.manager.ai.AnthropicApiClient;
import com.household.manager.ai.AnthropicRequest;
import com.household.manager.ai.AnthropicResponse;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.pricing.BaseUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchWordSuggesterTest {

    private final AnthropicApiClient client = mock(AnthropicApiClient.class);
    private final SearchWordSuggester suggester = new SearchWordSuggester(client, new GroceryProperties());

    private static AnthropicResponse text(String text) {
        return new AnthropicResponse("end_turn", List.of(Map.of("type", "text", "text", text)));
    }

    @Test
    void suggesterFallsBackWithoutClaude() {
        when(client.createMessage(any(AnthropicRequest.class))).thenThrow(new AiException("kein Schluessel", null));

        SearchWordSuggester.Suggestion s = suggester.suggest("Coca-Cola Zero 0,33 l Dose");

        assertThat(s.searchWords()).contains("coca-cola", "zero").doesNotContain("dose", "0,33");
        assertThat(s.baseUnit()).isEqualTo(BaseUnit.LITER);
    }

    @Test
    void suggesterUsesClaudeAnswerInsideFence() {
        when(client.createMessage(any(AnthropicRequest.class))).thenReturn(
                text("```json\n{\"searchWords\":[\"Coca-Cola\",\"coca-cola\",\"Zero\"],\"baseUnit\":\"LITER\"}\n```"));

        SearchWordSuggester.Suggestion s = suggester.suggest("Coca-Cola Zero");

        assertThat(s.searchWords()).containsExactly("coca-cola", "zero");
        assertThat(s.baseUnit()).isEqualTo(BaseUnit.LITER);
    }

    @Test
    void suggesterFallsBackOnUnknownUnit() {
        when(client.createMessage(any(AnthropicRequest.class)))
                .thenReturn(text("{\"searchWords\":[\"butter\"],\"baseUnit\":\"BOTTLE\"}"));

        SearchWordSuggester.Suggestion s = suggester.suggest("Butter 250 g");

        assertThat(s.searchWords()).containsExactly("butter");
        assertThat(s.baseUnit()).isEqualTo(BaseUnit.KILOGRAM);
    }

    @Test
    void fallbackUsesPieceByDefault() {
        when(client.createMessage(any(AnthropicRequest.class))).thenThrow(new AiException("x", null));

        assertThat(suggester.suggest("Eier Freiland").baseUnit()).isEqualTo(BaseUnit.PIECE);
    }

    @Test
    void fallbackKeepsDigitTokens() {
        when(client.createMessage(any(AnthropicRequest.class))).thenThrow(new AiException("x", null));

        assertThat(suggester.suggest("7up").searchWords()).containsExactly("7up");
        assertThat(suggester.suggest("S-Budget 1a").searchWords()).isNotEmpty().contains("s-budget");
    }

    @Test
    void commasInClaudeWordsAreRemoved() {
        when(client.createMessage(any(AnthropicRequest.class)))
                .thenReturn(text("{\"searchWords\":[\"cola, zero\"],\"baseUnit\":\"LITER\"}"));

        assertThat(suggester.suggest("Cola Zero").searchWords()).containsExactly("cola zero");
    }
}
