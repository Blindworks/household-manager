package com.household.manager.accountlogin;

/** Anmeldezustand eines Dienstes. */
public enum AccountLoginState {
    /** Angemeldet. */
    OK,
    /** Angemeldet, die Anmeldung läuft aber bald ab (nur Tractive kennt das Ablaufdatum). */
    EXPIRING,
    /** Nicht (mehr) angemeldet bzw. Kopplung abgelehnt. */
    EXPIRED,
    /** Nie eingerichtet oder abgeschaltet: kein Grund zu warnen. */
    NOT_SET_UP
}
