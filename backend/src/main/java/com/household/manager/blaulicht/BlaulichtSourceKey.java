package com.household.manager.blaulicht;

/** Herkunft einer Blaulicht-Meldung; steht als Text in {@code blaulicht_event.source}. */
public enum BlaulichtSourceKey {
    /** Presseportal Blaulicht, Polizeipräsidium Mittelhessen (Wetteraukreis). */
    PRESSEPORTAL,
    /** NINA / warnung.bund.de (MoWaS, KatWarn, BIWAPP, Polizei, Hochwasser). */
    NINA
}
