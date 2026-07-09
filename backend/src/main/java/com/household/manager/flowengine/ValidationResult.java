package com.household.manager.flowengine;

import java.util.List;

/** Ergebnis der Deploy-Validierung: Fehler blockieren, Warnungen nicht. */
public record ValidationResult(List<String> errors, List<String> warnings) {

    public boolean valid() {
        return errors.isEmpty();
    }
}
