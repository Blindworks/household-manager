package com.household.manager.accountlogin;

/** Externe Dienste mit eigener Anmeldung, deren Ablauf gemeldet wird. */
public enum AccountService {
    TRACTIVE("tractive", "Tractive"),
    BLINK("blink", "Blink"),
    ALEXA("alexa", "Amazon Alexa"),
    DIRIGERA("dirigera", "IKEA Dirigera");

    private final String slug;
    private final String label;

    AccountService(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    /** Stabiler Teil der Entity-Id; nie ändern, sonst läuft ein Flow darauf ins Leere. */
    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }
}
