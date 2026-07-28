package com.household.manager.zigbee.service;

/**
 * Erlaubt dem Watchdog, die MQTT-Verbindung neu aufzubauen, ohne den Client zu kennen.
 */
public interface ZigbeeConnectionControl {

    /**
     * Trennt die Verbindung und baut sie samt Subscription neu auf.
     * Darf nie werfen — der Aufrufer ist ein Scheduler.
     */
    void forceReconnect();
}
