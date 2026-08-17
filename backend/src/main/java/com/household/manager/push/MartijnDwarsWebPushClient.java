package com.household.manager.push;

import com.household.manager.model.entity.PushSubscription;
import lombok.RequiredArgsConstructor;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;

/**
 * Einzige Stelle mit nl.martijndwars:web-push-Spezifika. Der PushService wird
 * lazy gebaut, damit die VAPID-Schluessel erst beim ersten Versand erzeugt
 * werden muessen (nicht beim Boot).
 */
@Component
@RequiredArgsConstructor
public class MartijnDwarsWebPushClient implements WebPushClient {

    /** VAPID-Subject: Kontakt fuer den Push-Dienst-Betreiber (Apple verlangt einen validen Wert). */
    private static final String VAPID_SUBJECT = "mailto:benedikt.lind@gmail.com";

    private final VapidKeyService vapidKeyService;
    private volatile PushService pushService;

    @Override
    public int send(PushSubscription subscription, String payload) throws Exception {
        Notification notification = new Notification(
                subscription.getEndpoint(),
                subscription.getP256dhKey(),
                subscription.getAuthSecret(),
                payload.getBytes(StandardCharsets.UTF_8));
        HttpResponse response = pushService().send(notification);
        return response.getStatusLine().getStatusCode();
    }

    private PushService pushService() throws GeneralSecurityException {
        if (pushService == null) {
            synchronized (this) {
                if (pushService == null) {
                    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                        Security.addProvider(new BouncyCastleProvider());
                    }
                    VapidKeyService.VapidKeys keys = vapidKeyService.keyPair();
                    pushService = new PushService(keys.publicKey(), keys.privateKey(), VAPID_SUBJECT);
                }
            }
        }
        return pushService;
    }
}
