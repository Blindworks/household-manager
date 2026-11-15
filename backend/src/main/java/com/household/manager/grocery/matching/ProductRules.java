package com.household.manager.grocery.matching;

import com.household.manager.grocery.entity.GroceryProduct;

import java.util.List;

/** Harte Regeln eines Produkts: Ausschlusswörter und Pflichtwörter, jeweils als ganze Wörter. */
public final class ProductRules {

    private ProductRules() {
    }

    public static boolean excludes(GroceryProduct product, String offerText) {
        List<String> tokens = ProductWords.tokens(offerText);
        for (String word : ProductWords.split(product.getExcludeWords())) {
            if (ProductWords.containsPhrase(tokens, word)) {
                return true;
            }
        }
        for (String word : ProductWords.split(product.getMustWords())) {
            if (!ProductWords.containsPhrase(tokens, word)) {
                return true;
            }
        }
        return false;
    }
}
