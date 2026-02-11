package com.household.manager.kasa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.exception.KasaCommunicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class KasaDiscoveryService {

    private static final int KASA_PORT = 9999;
    private static final int RECEIVE_BUFFER_SIZE = 4096;
    private static final String DISCOVERY_PAYLOAD = "{\"system\":{\"get_sysinfo\":{}}}";

    private final KasaCrypto kasaCrypto;
    private final ObjectMapper objectMapper;

    @Value("${kasa.discovery.timeout-ms:2000}")
    private int discoveryTimeoutMs;

    public List<KasaDiscoveryDto> discover() {
        Map<String, KasaDiscoveryDto> devicesByIp = new LinkedHashMap<>();
        byte[] requestBytes = kasaCrypto.encryptPayload(DISCOVERY_PAYLOAD.getBytes(StandardCharsets.UTF_8));

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(discoveryTimeoutMs);

            DatagramPacket requestPacket = new DatagramPacket(
                    requestBytes,
                    requestBytes.length,
                    InetAddress.getByName("255.255.255.255"),
                    KASA_PORT
            );

            log.info("Sending Kasa discovery broadcast to 255.255.255.255:{} with timeout={}ms", KASA_PORT, discoveryTimeoutMs);
            socket.send(requestPacket);

            while (true) {
                try {
                    byte[] receiveBuffer = new byte[RECEIVE_BUFFER_SIZE];
                    DatagramPacket responsePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    socket.receive(responsePacket);

                    String sourceIp = responsePacket.getAddress().getHostAddress();
                    byte[] encryptedResponse = new byte[responsePacket.getLength()];
                    System.arraycopy(responsePacket.getData(), responsePacket.getOffset(), encryptedResponse, 0, responsePacket.getLength());

                    String responseJson = new String(kasaCrypto.decryptPayload(encryptedResponse), StandardCharsets.UTF_8);
                    log.info("Received Kasa discovery response from {}", sourceIp);
                    log.debug("Kasa discovery raw response from {}: {}", sourceIp, responseJson);

                    KasaDiscoveryDto device = mapDiscoveryDto(sourceIp, responseJson);
                    devicesByIp.put(sourceIp, device);
                } catch (SocketTimeoutException ex) {
                    log.info("Kasa discovery receive timeout reached after {} ms", discoveryTimeoutMs);
                    break;
                }
            }

            return new ArrayList<>(devicesByIp.values());
        } catch (IOException ex) {
            throw new KasaCommunicationException("Failed to discover Kasa devices on local network", ex);
        }
    }

    private KasaDiscoveryDto mapDiscoveryDto(String ip, String responseJson) throws IOException {
        JsonNode sysInfo = objectMapper.readTree(responseJson)
                .path("system")
                .path("get_sysinfo");

        KasaDiscoveryDto dto = new KasaDiscoveryDto();
        dto.setIp(ip);
        dto.setDeviceId(sysInfo.path("deviceId").asText(null));
        dto.setModel(sysInfo.path("model").asText(null));
        dto.setAlias(sysInfo.path("alias").asText(null));
        dto.setRelayState(sysInfo.path("relay_state").asInt(0) == 1);
        return dto;
    }
}
