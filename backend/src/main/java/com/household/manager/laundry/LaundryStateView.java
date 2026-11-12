package com.household.manager.laundry;

import java.time.LocalDateTime;

/**
 * Sicht auf den laufenden Waschgang fuer Entitaet, API und Tablet.
 *
 * @param status idle | asking | drying | maybe_dry
 * @param prompt NONE | HUNG | DRY — welche Frage das Tablet gerade stellt
 */
public record LaundryStateView(String status, String prompt, LocalDateTime startedAt,
                               LocalDateTime predictedDryAt, boolean estimatePaused, double requiredLoad) {

    public static final String IDLE = "idle";
    public static final String PROMPT_NONE = "NONE";
    public static final String PROMPT_HUNG = "HUNG";
    public static final String PROMPT_DRY = "DRY";
}
