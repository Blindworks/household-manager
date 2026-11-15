package com.household.manager.grocery.matching;

import com.household.manager.grocery.entity.GroceryOffer;
import com.household.manager.grocery.entity.GroceryProduct;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidatePrefilterTest {

    private final CandidatePrefilter prefilter = new CandidatePrefilter();

    private static GroceryOffer offer(String title) {
        GroceryOffer o = new GroceryOffer();
        o.setTitle(title);
        return o;
    }

    private static GroceryProduct product(String search, String exclude) {
        GroceryProduct p = new GroceryProduct();
        p.setSearchWords(search);
        p.setMustWords("");
        p.setExcludeWords(exclude);
        return p;
    }

    @Test
    void prefilterFindsVerschSorten() {
        GroceryOffer cola = offer("Coca-Cola, versch. Sorten");
        GroceryOffer other = offer("Pepsi Max");

        assertThat(prefilter.candidates(product("coca-cola", ""), List.of(cola, other))).containsExactly(cola);
    }

    @Test
    void prefilterAppliesHardRules() {
        assertThat(prefilter.candidates(product("cola", "fanta"), List.of(offer("Cola oder Fanta")))).isEmpty();
    }
}
