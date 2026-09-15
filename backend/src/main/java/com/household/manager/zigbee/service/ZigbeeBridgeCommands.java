package com.household.manager.zigbee.service;

import java.util.concurrent.CompletableFuture;

/**
 * Erlaubt dem Request-Service, auf dem MQTT-Client zu publizieren, ohne ihn zu kennen
 * (Muster {@link ZigbeeConnectionControl}).
 */
public interface ZigbeeBridgeCommands {

    boolean isConnected();

    /** Publiziert mit QoS 1. Das Future schlaegt fehl, wenn der Broker nicht bestaetigt. */
    CompletableFuture<Void> publish(String topic, String payload);
}
