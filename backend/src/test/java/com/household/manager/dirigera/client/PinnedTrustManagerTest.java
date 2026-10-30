package com.household.manager.dirigera.client;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PinnedTrustManagerTest {

    /** Ausgabe von {@code openssl x509 -in test-cert.pem -noout -fingerprint -sha256}. */
    static final String EXPECTED =
            "3B:EB:71:FC:C3:55:8C:4B:D1:EF:CC:F5:32:53:FA:B9:5B:83:23:6A:03:A8:34:2A:06:E6:1F:1D:36:AF:09:8B";

    private static final String OTHER =
            "63:26:C5:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00";

    private static X509Certificate[] chain;

    @BeforeAll
    static void loadCertificate() throws Exception {
        try (InputStream in = PinnedTrustManagerTest.class.getResourceAsStream("/dirigera/test-cert.pem")) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            chain = new X509Certificate[] {cert};
        }
    }

    @Test
    void akzeptiertGemerktenFingerabdruck() {
        assertThatCode(() -> new PinnedTrustManager(EXPECTED).checkServerTrusted(chain, "ECDHE_ECDSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void akzeptiertFingerabdruckOhneDoppelpunkteKleingeschrieben() {
        String compact = EXPECTED.replace(":", "").toLowerCase();

        assertThatCode(() -> new PinnedTrustManager(compact).checkServerTrusted(chain, "ECDHE_ECDSA"))
                .doesNotThrowAnyException();
    }

    @Test
    void lehntAnderesZertifikatAb() {
        assertThatThrownBy(() -> new PinnedTrustManager(OTHER).checkServerTrusted(chain, "ECDHE_ECDSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void lehntAnderesZertifikatAuchImEngineHandshakeAb() throws Exception {
        // Diesen Pfad nimmt java.net.http.HttpClient; er muss genauso pinnen.
        SSLEngine engine = SSLContext.getDefault().createSSLEngine("127.0.0.1", 8443);

        assertThatThrownBy(() -> new PinnedTrustManager(OTHER).checkServerTrusted(chain, "ECDHE_ECDSA", engine))
                .isInstanceOf(CertificateException.class);
        assertThatCode(() -> new PinnedTrustManager(EXPECTED).checkServerTrusted(chain, "ECDHE_ECDSA", engine))
                .doesNotThrowAnyException();
    }

    @Test
    void istErweiterterTrustManagerDamitDasJdkKeinenHostnamenPrueft() {
        // Ein einfacher X509TrustManager würde vom JDK in einen Wrapper gepackt, der den
        // Hostnamen gegen das Zertifikat prüft — das Hub-Zertifikat trägt keine IP.
        assertThat(new PinnedTrustManager(EXPECTED)).isInstanceOf(X509ExtendedTrustManager.class);
    }

    @Test
    void lehntLeereKetteAb() {
        assertThatThrownBy(() -> new PinnedTrustManager(EXPECTED).checkServerTrusted(new X509Certificate[0], "ECDHE_ECDSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void lehntUngueltigenFingerabdruckImKonstruktorAb() {
        assertThatThrownBy(() -> new PinnedTrustManager("3B:EB"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintImOpensslFormat() {
        assertThat(PinnedTrustManager.fingerprint(chain[0])).isEqualTo(EXPECTED);
    }
}
