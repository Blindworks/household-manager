package com.household.manager.blaulicht.processing;

import com.household.manager.blaulicht.BlaulichtCategory;

import java.util.Optional;

/**
 * Ordnet einen Fall einer Kategorie zu. Heute per Schlüsselwörtern ({@link KeywordBlaulichtClassifier});
 * eine LLM-Variante kann als weitere Bean mit {@code @Primary} daneben gestellt werden.
 *
 * @return leer, wenn der Fall verworfen wird (reiner Rettungsdiensteinsatz)
 */
public interface BlaulichtClassifier {

    Optional<BlaulichtCategory> classify(String headline, String body);
}
