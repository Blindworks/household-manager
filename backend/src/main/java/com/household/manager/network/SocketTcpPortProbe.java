package com.household.manager.network;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Default-Implementierung von {@link TcpPortProbe} ueber einen einfachen Socket-Connect.
 */
@Component
@Slf4j
public class SocketTcpPortProbe implements TcpPortProbe {

    @Override
    public boolean isOpen(String host, int port, Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (Exception e) {
            log.debug("TCP-Port {}:{} nicht erreichbar: {}", host, port, e.getMessage());
            return false;
        }
    }
}
