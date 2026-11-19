package com.household.manager.grocery.pricing;

import com.household.manager.ai.AnthropicApiClient;
import com.household.manager.ai.AnthropicRequest;
import com.household.manager.ai.AnthropicResponse;
import com.household.manager.grocery.Chain;
import com.household.manager.grocery.GroceryProperties;
import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NormalPriceEstimatorTest {

    private final AnthropicApiClient client = mock(AnthropicApiClient.class);
    private final NormalPriceEstimator estimator = new NormalPriceEstimator(client, new GroceryProperties());

    private static GroceryProduct coffee() {
        GroceryProduct p = new GroceryProduct();
        p.setId(2L);
        p.setName("Kaffee ganze Bohnen 1kg");
        p.setBaseUnit(BaseUnit.KILOGRAM);
        return p;
    }

    private static GroceryOffer lidlOffer() {
        GroceryOffer o = new GroceryOffer();
        o.setChain(Chain.LIDL);
        o.setTitle("Caffè Crema");
        o.setPackaging("1 kg");
        o.setPrice(new BigDecimal("13.99"));
        o.setBasePrice(new BigDecimal("13.990"));
        o.setBaseUnit(BaseUnit.KILOGRAM);
        o.setOldPrice(new BigDecimal("19.99"));
        return o;
    }

    @Test
    void parsesPriceAndRoundsToCents() {
        assertThat(NormalPriceEstimator.parse("{\"pricePerUnit\": 18.987}")).contains(new BigDecimal("18.99"));
        assertThat(NormalPriceEstimator.parse("```json\n{\"pricePerUnit\": 19}\n```")).contains(new BigDecimal("19.00"));
    }

    @Test
    void rejectsImplausibleOrBrokenAnswers() {
        assertThat(NormalPriceEstimator.parse("{\"pricePerUnit\": 0}")).isEmpty();
        assertThat(NormalPriceEstimator.parse("{\"pricePerUnit\": -3}")).isEmpty();
        assertThat(NormalPriceEstimator.parse("{\"pricePerUnit\": 900}")).isEmpty();
        assertThat(NormalPriceEstimator.parse("{\"pricePerUnit\": \"19\"}")).isEmpty();
        assertThat(NormalPriceEstimator.parse("etwa 19 Euro")).isEmpty();
        assertThat(NormalPriceEstimator.parse(null)).isEmpty();
    }

    @Test
    void promptCarriesProductUnitAndOffersAsHints() {
        String text = NormalPriceEstimator.userText(coffee(), List.of(lidlOffer()));

        assertThat(text).contains("Produkt: Kaffee ganze Bohnen 1kg");
        assertThat(text).contains("Bezugseinheit: je Kilogramm");
        assertThat(text).contains("LIDL: Caffè Crema, 1 kg, Preis 13.99 €, 13.99 € je Kilogramm, Streichpreis 19.99 €");
    }

    @Test
    void estimateUsesClaudeAnswer() {
        when(client.createMessage(any())).thenReturn(new AnthropicResponse("end_turn",
                List.of(Map.of("type", "text", "text", "{\"pricePerUnit\": 18.5}")), 120, 10));

        assertThat(estimator.estimate(coffee(), List.of(lidlOffer()))).contains(new BigDecimal("18.50"));
        verify(client).createMessage(any(AnthropicRequest.class));
    }

    @Test
    void claudeFailureGivesNoEstimate() {
        when(client.createMessage(any())).thenThrow(new IllegalStateException("weg"));

        assertThat(estimator.estimate(coffee(), List.of())).isEmpty();
    }
}
