package com.household.manager.presence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

/**
 * Socket-Implementierung: erste Antwort gewinnt. Ein {@link ConnectException}
 * (Connection refused) ist eine Antwort — nur Timeouts und Routing-Fehler
 * zaehlen als Stille.
 */
@Component
@Slf4j
public class SocketPresenceProbe implements PresenceProbe {

    @Override
    public ProbeResult probe(String host, List<Integer> ports, Duration timeoutPerPort) {
        for (int port : ports) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), (int) timeoutPerPort.toMillis());
                return ProbeResult.RESPONDED;
            } catch (ConnectException e) {
                // Aktive Ablehnung (RST): der Host lebt.
                return ProbeResult.RESPONDED;
            } catch (Exception e) {
                log.debug("Keine Antwort von {}:{}: {}", host, port, e.getMessage());
            }
        }
        return ProbeResult.SILENT;
    }
}
