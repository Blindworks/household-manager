package com.household.manager.common;

/** Lesbarer Fehlergrund fuer Protokoll und Zustellergebnis. */
public final class FailureReasons {

    private FailureReasons() {
    }

    /** Die Meldung der Exception; ohne Meldung (z. B. NullPointerException) wenigstens ihr Typ. */
    public static String of(Throwable ex) {
        if (ex == null) {
            return "unbekannter Fehler";
        }
        String message = ex.getMessage();
        return message != null && !message.isBlank() ? message : ex.getClass().getSimpleName();
    }
}
