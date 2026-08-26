package com.household.manager.presence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SocketPresenceProbeTest {

    private final SocketPresenceProbe probe = new SocketPresenceProbe();

    @Test
    void offenerPortZaehltAlsAntwort() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            ProbeResult result = probe.probe("127.0.0.1",
                    List.of(server.getLocalPort()), Duration.ofSeconds(1));
            assertThat(result).isEqualTo(ProbeResult.RESPONDED);
        }
    }

    @Test
    void abgelehnteVerbindungZaehltAlsAntwort() throws IOException {
        // Port kurz belegen und wieder freigeben: connect dahin liefert "refused" (RST),
        // und genau das beweist, dass der Host lebt.
        int freedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            freedPort = server.getLocalPort();
        }
        ProbeResult result = probe.probe("127.0.0.1", List.of(freedPort), Duration.ofSeconds(1));
        assertThat(result).isEqualTo(ProbeResult.RESPONDED);
    }

    @Test
    void timeoutAufAllenPortsIstStille() {
        // 192.0.2.1 (TEST-NET-1) ist nicht geroutet -> Connect laeuft in den Timeout.
        ProbeResult result = probe.probe("192.0.2.1", List.of(80), Duration.ofMillis(200));
        assertThat(result).isEqualTo(ProbeResult.SILENT);
    }
}
