package com.household.manager.push;

import com.household.manager.model.entity.PushSubscription;

/**
 * Duenne Abstraktion ueber die Web-Push-Library — die einzige Stelle mit
 * Library-Spezifika ist die Implementierung, und der PushNotificationService
 * bleibt ohne echte Krypto testbar.
 */
public interface WebPushClient {

    /** Sendet die Payload an die Subscription und liefert den HTTP-Status des Push-Dienstes. */
    int send(PushSubscription subscription, String payload) throws Exception;
}
