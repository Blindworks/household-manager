package com.household.manager.push;

import com.household.manager.service.ApplicationSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;
import java.util.Map;

/**
 * VAPID-Schluesselpaar in application_settings (Kategorie PUSH_VAPID), beim
 * ersten Zugriff automatisch erzeugt — bewusst keine Env-Variable, damit der
 * Rollout keinen manuellen Schritt braucht.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VapidKeyService {

    static final String CATEGORY = "PUSH_VAPID";
    static final String KEY_PUBLIC = "publicKey";
    static final String KEY_PRIVATE = "privateKey";

    private final ApplicationSettingsService settings;
    private final Object lock = new Object();

    public String publicKey() {
        return keyPair().publicKey();
    }

    public VapidKeys keyPair() {
        synchronized (lock) {
            String publicKey = settings.getString(CATEGORY, KEY_PUBLIC, null);
            String privateKey = settings.getString(CATEGORY, KEY_PRIVATE, null);
            if (publicKey != null && privateKey != null) {
                return new VapidKeys(publicKey, privateKey);
            }
            VapidKeys generated = generate();
            settings.saveSettings(CATEGORY, Map.of(
                    KEY_PUBLIC, generated.publicKey(),
                    KEY_PRIVATE, generated.privateKey()));
            log.info("VAPID-Schluesselpaar erzeugt und gespeichert");
            return generated;
        }
    }

    private VapidKeys generate() {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
            generator.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"), new SecureRandom());
            KeyPair pair = generator.generateKeyPair();
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return new VapidKeys(
                    encoder.encodeToString(Utils.encode((ECPublicKey) pair.getPublic())),
                    encoder.encodeToString(Utils.encode((ECPrivateKey) pair.getPrivate())));
        } catch (Exception ex) {
            throw new IllegalStateException("VAPID-Schluesselerzeugung fehlgeschlagen", ex);
        }
    }

    public record VapidKeys(String publicKey, String privateKey) {}
}
