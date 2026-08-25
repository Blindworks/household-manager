package com.household.manager.network;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;

/**
 * Misst Down-/Upload-Rate gegen den oeffentlichen Cloudflare-Speedtest-Endpunkt.
 * Bewusst duenn und NICHT gegen das echte Netz unit-getestet - die Berechnungslogik
 * (Bytes+Nanosekunden -&gt; Mbit/s) steckt dafuer in {@link #mbps} und ist eigenstaendig
 * testbar.
 * <p>
 * HTTP_1_1 ist erzwungen (bekannte Projekt-Falle, siehe {@link HttpConnectivityProbe}).
 */
@Component
public class CloudflareSpeedtestClient implements SpeedtestClient {

    // Gemessen (2026-08-25, echtes PROD-Netz): bytes=100000000/200000000/250000000 liefern
    // 403 Forbidden mit Content-Length: 1 statt der vollen Nutzlast; bytes=25000000/50000000/90000000
    // liefern 200 OK mit vollem Body. <100 MB ist damit das beobachtete Limit dieses Endpunkts.
    // 50 MB liegt sicher darunter; die Schleife in measureDownloadMbps holt bei Bedarf mehrere
    // Bloecke nach, bis das Zeitbudget ausgeschoepft ist.
    private static final URI DOWNLOAD_URI = URI.create("https://speed.cloudflare.com/__down?bytes=50000000");
    private static final URI UPLOAD_URI = URI.create("https://speed.cloudflare.com/__up");
    private static final int BUFFER_SIZE = 64 * 1024;

    @Override
    public BigDecimal measureDownloadMbps(Duration budget) throws IOException, InterruptedException {
        HttpClient client = newClient();

        long totalBytes = 0;
        long startNanos = -1;
        long deadlineNanos = -1;
        while (true) {
            HttpRequest request = HttpRequest.newBuilder(DOWNLOAD_URI)
                    .timeout(budget.plusSeconds(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // Ohne diese Pruefung wird eine Cloudflare-Fehlseite (403 bei zu grossem bytes=,
            // Content-Length: 1) als winzige, aber "erfolgreiche" Messung verbucht - genau das hat
            // den PROD-Vorfall vom 2026-08-25 verursacht (Download 0.01 Mbit/s bei 1000er Glasfaser).
            if (response.statusCode() >= 400) {
                throw new IOException("Cloudflare-Speedtest antwortete HTTP " + response.statusCode());
            }

            try (InputStream body = response.body()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = body.read(buffer)) != -1) {
                    if (startNanos < 0) {
                        startNanos = System.nanoTime();
                        deadlineNanos = startNanos + budget.toNanos();
                    }
                    totalBytes += read;
                    if (System.nanoTime() >= deadlineNanos) {
                        long elapsedNanos = System.nanoTime() - startNanos;
                        return mbps(totalBytes, elapsedNanos);
                    }
                }
            }
            if (deadlineNanos >= 0 && System.nanoTime() >= deadlineNanos) {
                break;
            }
        }
        long elapsedNanos = startNanos < 0 ? 0 : System.nanoTime() - startNanos;
        return mbps(totalBytes, elapsedNanos);
    }

    @Override
    public BigDecimal measureUploadMbps(Duration budget) throws IOException, InterruptedException {
        HttpClient client = newClient();
        BudgetedRandomInputStream body = new BudgetedRandomInputStream(budget);
        HttpRequest request = HttpRequest.newBuilder(UPLOAD_URI)
                .timeout(budget.plusSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> body))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 400) {
            throw new IOException("Cloudflare-Speedtest antwortete HTTP " + response.statusCode());
        }
        return mbps(body.bytesSent(), body.elapsedNanos());
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** Bytes+Nanosekunden -&gt; Mbit/s, Scale 2, HALF_UP. Package-private, damit der Test sie direkt aufrufen kann. */
    static BigDecimal mbps(long bytes, long nanos) {
        if (nanos <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal seconds = BigDecimal.valueOf(nanos).divide(BigDecimal.valueOf(1_000_000_000L), 9, RoundingMode.HALF_UP);
        BigDecimal bits = BigDecimal.valueOf(bytes).multiply(BigDecimal.valueOf(8));
        BigDecimal megabits = bits.divide(BigDecimal.valueOf(1_000_000L), 9, RoundingMode.HALF_UP);
        return megabits.divide(seconds, 2, RoundingMode.HALF_UP);
    }

    /**
     * Liefert Zufallsbytes, bis das Zeitbudget abgelaufen ist, dann EOF - eine feste
     * Byte-Menge laesst sich fuer den Upload nicht sinnvoll schaetzen.
     */
    private static final class BudgetedRandomInputStream extends InputStream {

        private final Duration budget;
        private final Random random = new Random();
        private long startNanos = -1;
        private long deadlineNanos = -1;
        private long bytesSent = 0;

        private BudgetedRandomInputStream(Duration budget) {
            this.budget = budget;
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (startNanos < 0) {
                startNanos = System.nanoTime();
                deadlineNanos = startNanos + budget.toNanos();
            }
            if (System.nanoTime() >= deadlineNanos) {
                return -1;
            }
            if (off == 0 && len == b.length) {
                random.nextBytes(b);
            } else {
                byte[] chunk = new byte[len];
                random.nextBytes(chunk);
                System.arraycopy(chunk, 0, b, off, len);
            }
            bytesSent += len;
            return len;
        }

        long bytesSent() {
            return bytesSent;
        }

        long elapsedNanos() {
            return startNanos < 0 ? 0 : System.nanoTime() - startNanos;
        }
    }
}
