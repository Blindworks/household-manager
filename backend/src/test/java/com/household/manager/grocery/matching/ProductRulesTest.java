package com.household.manager.grocery.matching;

import com.household.manager.grocery.entity.GroceryProduct;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRulesTest {

    private static GroceryProduct product(String must, String exclude) {
        GroceryProduct p = new GroceryProduct();
        p.setMustWords(must);
        p.setExcludeWords(exclude);
        return p;
    }

    @Test
    void excludeWordRemovesFanta() {
        assertThat(ProductRules.excludes(product("", "fanta"), "COCA-COLA oder FANTA Exotic")).isTrue();
    }

    @Test
    void allMustWordsRequired() {
        GroceryProduct p = product("cola, dose", "");
        assertThat(ProductRules.excludes(p, "Coca-Cola Flasche")).isTrue();
        assertThat(ProductRules.excludes(p, "Coca-Cola Dose")).isFalse();
    }

    @Test
    void hyphenEqualsSpace() {
        assertThat(ProductRules.excludes(product("coca-cola", ""), "Coca Cola 0,33 l")).isFalse();
        assertThat(ProductRules.excludes(product("coca cola", ""), "Coca-Cola 0,33 l")).isFalse();
    }

    @Test
    void wholeWordsOnly() {
        assertThat(ProductRules.excludes(product("cola", ""), "Chocolate Riegel")).isTrue();
        assertThat(ProductRules.excludes(product("", "cola"), "Chocolate Riegel")).isFalse();
    }
}
