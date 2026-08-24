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
    /** Tractive GPS-Haustiertracker (inoffizielle Cloud-API). */
    TRACTIVE,
    /** Haushaltskalender (interne Termine, event.calendar_reminder). */
    CALENDAR,
    /** Serverseitig berechnete Hinweise (z. B. Lüftungsempfehlung). */
    INSIGHT,
    /** Toni-Futtervorrat (interner Bestand, sensor.pet_food_toni_cans). */
    PET_FOOD,
    /** Vom Benutzer im UI angelegte Entität (kein externes Quellsystem). */
    MANUAL,
    /** Internet-Konnektivitäts- und Latenzmessung (interner Poller, kein externes Quellsystem). */
    NETWORK
}
