package com.household.manager.entitystate;

/**
 * Herkunftsintegration einer Entität.
 */
public enum EntitySource {
    KASA,
    TAPO,
    MEROSS,
    ZIGBEE,
    SHELLY,
    TASMOTA,
    AIRROHR,
    ALEXA,
    WEATHER,
    ANKER_SOLIX,
    /** Muellabfuhr-Termine aus dem Kalender-Abo. */
    WASTE,
    /** Wandtablet-App (Präsenzerkennung per Frontkamera). */
    TABLET,
    /** Nuki Smart Lock (Web API). */
    NUKI,
    /** Blink-Gesichtserkennung (blink-vision-Sidecar). */
    VISION,
    /** Haushaltskalender (interne Termine, event.calendar_reminder). */
    CALENDAR,
    /** Vom Benutzer im UI angelegte Entität (kein externes Quellsystem). */
    MANUAL
}
