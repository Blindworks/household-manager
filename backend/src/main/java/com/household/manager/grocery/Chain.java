package com.household.manager.grocery;

/** Supermarktketten und Getränkemärkte, deren Angebote beobachtet werden. */
public enum Chain {
    REWE("REWE", true),
    PENNY("Penny", true),
    KAUFLAND("Kaufland", true),
    EDEKA("Edeka", true),
    LIDL("Lidl", false),
    ALDI_SUED("Aldi Süd", false),
    NETTO_MD("Netto", false),
    NORMA("Norma", false),
    LOGO("logo Getränke", false),
    TRINKGUT("trinkgut", false),
    ALLDRINK("alldrink", false);

    private final String displayName;
    private final boolean hasMarkets;

    Chain(String displayName, boolean hasMarkets) {
        this.displayName = displayName;
        this.hasMarkets = hasMarkets;
    }

    public String displayName() {
        return displayName;
    }

    /** Ob Angebote je Markt gelten (und der Markt gewählt werden muss) statt kettenweit. */
    public boolean hasMarkets() {
        return hasMarkets;
    }
}
