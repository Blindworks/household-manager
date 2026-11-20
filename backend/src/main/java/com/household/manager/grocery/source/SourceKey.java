package com.household.manager.grocery.source;

/** Angebotsquellen; Aggregatoren liefern mehrere Ketten, die übrigen genau eine. */
public enum SourceKey {
    MARKTGURU(true),
    KAUFDA(true),
    REWE(false),
    PENNY(false),
    KAUFLAND(false),
    NORMA(false),
    LIDL(false),
    LOGO(false);

    private final boolean aggregator;

    SourceKey(boolean aggregator) {
        this.aggregator = aggregator;
    }

    public boolean isAggregator() {
        return aggregator;
    }
}
