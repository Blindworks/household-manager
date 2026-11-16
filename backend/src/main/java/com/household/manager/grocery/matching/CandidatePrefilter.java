package com.household.manager.grocery.matching;

import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Wählt aus gespeicherten Angeboten die Kandidaten für ein Produkt, bevor Claude urteilt. */
@Component
public class CandidatePrefilter {

    public List<GroceryOffer> candidates(GroceryProduct product, List<GroceryOffer> offers) {
        List<String> searchWords = ProductWords.split(product.getSearchWords());
        return offers.stream()
                .filter(offer -> {
                    String text = offerText(offer);
                    List<String> tokens = ProductWords.tokens(text);
                    return searchWords.stream().anyMatch(w -> ProductWords.containsPhrase(tokens, w))
                            && !ProductRules.excludes(product, text);
                })
                .toList();
    }

    /** Titel, Marke, Beschreibung und Packung; fehlende Teile entfallen. */
    static String offerText(GroceryOffer offer) {
        return Stream.of(offer.getTitle(), offer.getBrand(), offer.getDescription(), offer.getPackaging())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
    }
}
