package com.household.manager.push;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

/**
 * Einzige Stelle, die den BouncyCastle-Provider ("BC") registriert. Die web-push-Library
 * laedt Schluessel ueber {@code KeyFactory.getInstance(..., "BC")} — schon im Konstruktor
 * von {@code Notification}, nicht erst im PushService. Fehlt der Provider, scheitert jeder
 * Versand mit "no such provider: BC".
 */
final class BouncyCastleRegistration {

    private BouncyCastleRegistration() {
    }

    static synchronized void ensureRegistered() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
}
