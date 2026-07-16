package com.household.manager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Konfiguration des Muellabfuhr-Kalenders, wie sie die Einstellungsseite sieht. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WasteCollectionSettings {

    private boolean enabled;

    /** Geheime iCal-URL des Kalenders; leer erlaubt (Feature dann inaktiv). */
    private String icsUrl;

    /** Vorschau-Fenster in Tagen, einschliesslich heute; mindestens 1. */
    private int lookaheadDays;

    private boolean reminderEnabled;

    /** Uhrzeit der Durchsage im Format HH:mm. */
    private String reminderTime;

    /** Seriennummern der Ziel-Alexa-Geraete. */
    private List<String> reminderAlexaSerials;
}
