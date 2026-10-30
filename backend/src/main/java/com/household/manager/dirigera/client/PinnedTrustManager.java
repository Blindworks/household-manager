package com.household.manager.dirigera.client;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Vertraut genau einem Zertifikat, erkannt am SHA-256 des Leaf-Zertifikats. Der Hub hat ein
 * selbstsigniertes Zertifikat ohne IP im SAN; gepinnt wird beim Koppeln (TOFU).
 *
 * <p>Bewusst ein {@link X509ExtendedTrustManager}: einen einfachen {@code X509TrustManager}
 * packt das JDK in einen Wrapper, der zusätzlich den Hostnamen prüft, und
 * {@code java.net.http.HttpClient} setzt den Endpoint-Identification-Algorithmus immer auf
 * „HTTPS“ (ein {@code null} in den SSLParameters wird überschrieben). Ein erweiterter
 * TrustManager wird nicht gewrappt; die Identität ist hier allein der Fingerabdruck, ohne
 * die JVM-weite Property {@code jdk.internal.httpclient.disableHostnameVerification}.
 */
public final class PinnedTrustManager extends X509ExtendedTrustManager {

    private static final Pattern HEX_64 = Pattern.compile("[0-9A-F]{64}");

    private final String expectedSha256;

    public PinnedTrustManager(String expectedSha256) {
        this.expectedSha256 = normalize(expectedSha256);
    }

    /** SHA-256 des Zertifikats im Format von {@code openssl x509 -fingerprint -sha256}. */
    public static String fingerprint(X509Certificate certificate) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(hash);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("Fingerabdruck nicht berechenbar", e);
        }
    }

    /** Groß-/Kleinschreibung und Doppelpunkte egal; liefert 64 Hex-Zeichen in Großbuchstaben. */
    static String normalize(String sha256) {
        String compact = sha256 == null ? "" : sha256.replace(":", "").trim().toUpperCase(Locale.ROOT);
        if (!HEX_64.matcher(compact).matches()) {
            throw new IllegalArgumentException("Ungueltiger SHA-256-Fingerabdruck");
        }
        return compact;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("Hub hat kein Zertifikat geliefert");
        }
        String actual = fingerprint(chain[0]).replace(":", "");
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), expectedSha256.getBytes(StandardCharsets.US_ASCII))) {
            throw new CertificateException("Zertifikat des Hubs passt nicht zum gemerkten Fingerabdruck");
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("Keine Client-Zertifikate");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
