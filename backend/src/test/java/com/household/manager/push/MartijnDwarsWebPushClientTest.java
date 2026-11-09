package com.household.manager.push;

import com.household.manager.model.entity.PushSubscription;
import com.household.manager.service.ApplicationSettingsService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MartijnDwarsWebPushClientTest {

    @AfterEach
    void restoreProvider() {
        BouncyCastleRegistration.ensureRegistered();
    }

    /**
     * Nachstellung von PROD nach einem Neustart: die VAPID-Schluessel liegen schon in der
     * DB, also registriert {@link VapidKeyService} BouncyCastle nicht. Der erste Versand
     * scheiterte dann mit "no such provider: BC", weil {@code new Notification(...)} den
     * Provider vor dem Aufbau des PushService brauchte.
     */
    @Test
    void sendetNachNeustartOhneVorabRegistriertenProvider() throws Exception {
        VapidKeyService.VapidKeys storedKeys = generateVapidKeys();
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);

        ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
        when(settings.getString("PUSH_VAPID", "publicKey", null)).thenReturn(storedKeys.publicKey());
        when(settings.getString("PUSH_VAPID", "privateKey", null)).thenReturn(storedKeys.privateKey());
        MartijnDwarsWebPushClient client = new MartijnDwarsWebPushClient(new VapidKeyService(settings));

        Throwable failure = null;
        try {
            client.send(subscriptionToClosedPort(), "{\"title\":\"Test\"}");
        } catch (Exception ex) {
            failure = ex; // Verbindungsfehler zum geschlossenen Port ist erwartet
        }

        assertFalse(causedByMissingProvider(failure), "Versand scheiterte am fehlenden BC-Provider: " + failure);
        assertNotNull(Security.getProvider(BouncyCastleProvider.PROVIDER_NAME));
    }

    private static VapidKeyService.VapidKeys generateVapidKeys() {
        ApplicationSettingsService settings = mock(ApplicationSettingsService.class);
        when(settings.getString(eq("PUSH_VAPID"), anyString(), isNull())).thenReturn(null);
        return new VapidKeyService(settings).keyPair();
    }

    private static PushSubscription subscriptionToClosedPort() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey publicKey = (ECPublicKey) generator.generateKeyPair().getPublic();
        byte[] point = new byte[65];
        point[0] = 0x04;
        copyUnsigned(publicKey.getW().getAffineX().toByteArray(), point, 1);
        copyUnsigned(publicKey.getW().getAffineY().toByteArray(), point, 33);
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        PushSubscription subscription = new PushSubscription();
        subscription.setEndpoint("http://127.0.0.1:1/push");
        subscription.setP256dhKey(encoder.encodeToString(point));
        subscription.setAuthSecret(encoder.encodeToString(auth));
        return subscription;
    }

    private static void copyUnsigned(byte[] value, byte[] target, int offset) {
        int length = Math.min(value.length, 32);
        System.arraycopy(value, value.length - length, target, offset + 32 - length, length);
    }

    private static boolean causedByMissingProvider(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof NoSuchProviderException
                    || (t.getMessage() != null && t.getMessage().contains("no such provider"))) {
                return true;
            }
        }
        return false;
    }
}
