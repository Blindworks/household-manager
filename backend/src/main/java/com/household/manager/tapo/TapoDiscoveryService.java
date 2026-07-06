package com.household.manager.tapo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

@Service
public class TapoDiscoveryService {

    private final ObjectMapper objectMapper;

    public TapoDiscoveryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<TapoDiscoveryDevice> discoverLocalDevices(TapoProperties properties, TapoDeviceFactory deviceFactory) {
        validateConfiguration(properties);

        List<TapoDiscoveryDevice> devices = new ArrayList<>();
        for (DiscoveredEndpoint endpoint : discoverEndpoints(properties)) {
            try {
                TapoLocalDeviceConnection connection = deviceFactory.create(
                        endpoint.authProtocol(),
                        endpoint.ipAddress(),
                        properties.getEmail(),
                        properties.getPassword()
                );
                JsonNode info = connection.getDeviceInfo();
                devices.add(new TapoDiscoveryDevice(
                        endpoint.ipAddress(),
                        endpoint.authProtocol(),
                        text(info, "device_id", "deviceId"),
                        text(info, "model", "device_model"),
                        text(info, "nickname", "alias"),
                        info.path("device_on").asBoolean(false)
                ));
            } catch (Exception ignored) {
                // Best effort; unresolved devices are skipped.
            }
        }
        return devices;
    }

    private List<DiscoveredEndpoint> discoverEndpoints(TapoProperties properties) {
        List<DiscoveredEndpoint> results = new ArrayList<>();
        Set<String> seenIps = new HashSet<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout((int) properties.getLocalDiscoveryTimeoutMs());

            byte[] query = generateDiscoveryQuery();
            for (InetAddress target : resolveBroadcastTargets(properties)) {
                try {
                    socket.send(new DatagramPacket(query, query.length, target, 20002));
                } catch (IOException ex) {
                    // Some targets are unreachable per interface state (e.g. VPN adapters); keep trying the rest.
                }
            }

            long deadline = System.currentTimeMillis() + properties.getLocalDiscoveryTimeoutMs();
            while (System.currentTimeMillis() < deadline) {
                try {
                    byte[] buffer = new byte[4096];
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);

                    String ipAddress = response.getAddress().getHostAddress();
                    if (!seenIps.add(ipAddress)) {
                        continue;
                    }

                    JsonNode message = parseDiscoveryResponse(buffer, response.getLength());
                    results.add(new DiscoveredEndpoint(ipAddress, determineProtocol(message)));
                } catch (SocketTimeoutException ex) {
                    break;
                }
            }
        } catch (IOException ex) {
            throw new TapoException("Tapo-Discovery im lokalen Netzwerk fehlgeschlagen.", ex);
        }

        return results;
    }

    /**
     * The limited broadcast 255.255.255.255 leaves a multi-homed host (VPN, WSL,
     * LAN adapters) on only one interface, which may be the wrong one. Query the
     * subnet-directed broadcast address of every active IPv4 interface as well.
     */
    static Set<InetAddress> resolveBroadcastTargets(TapoProperties properties) {
        Set<InetAddress> targets = new LinkedHashSet<>();
        try {
            targets.add(InetAddress.getByName(properties.getLocalDiscoveryTarget()));
        } catch (IOException ex) {
            // Fall through to interface broadcasts.
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nic.getInterfaceAddresses()) {
                    if (address.getBroadcast() != null) {
                        targets.add(address.getBroadcast());
                    }
                }
            }
        } catch (SocketException ex) {
            // Interface enumeration is best effort; the configured target remains.
        }
        return targets;
    }

    private byte[] generateDiscoveryQuery() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            KeyPair keyPair = generator.generateKeyPair();

            String pem = toPkcs1Pem((RSAPublicKey) keyPair.getPublic());
            byte[] payload = objectMapper.createObjectNode()
                    .set("params", objectMapper.createObjectNode().put("rsa_key", pem))
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);

            byte[] header = ByteBuffer.allocate(16)
                    .put((byte) 2)
                    .put((byte) 0)
                    .putShort((short) 1)
                    .putShort((short) payload.length)
                    .put((byte) 17)
                    .put((byte) 0)
                    .putInt(new SecureRandom().nextInt())
                    .putInt(0x5A6B7C8D)
                    .array();

            byte[] query = new byte[header.length + payload.length];
            System.arraycopy(header, 0, query, 0, header.length);
            System.arraycopy(payload, 0, query, header.length, payload.length);

            CRC32 crc32 = new CRC32();
            crc32.update(query);
            byte[] crc = ByteBuffer.allocate(4).putInt((int) crc32.getValue()).array();
            System.arraycopy(crc, 0, query, 12, 4);
            return query;
        } catch (Exception ex) {
            throw new TapoException("Tapo-Discovery-Query konnte nicht erzeugt werden.", ex);
        }
    }

    private JsonNode parseDiscoveryResponse(byte[] bytes, int length) {
        if (length <= 16) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(new String(bytes, 16, length - 16, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return objectMapper.nullNode();
        }
    }

    private TapoAuthProtocol determineProtocol(JsonNode message) {
        JsonNode scheme = message.path("result").path("mgt_encrypt_schm");
        String encryptType = scheme.path("encrypt_type").asText(null);
        long httpPort = scheme.path("http_port").asLong(-1);
        if ("KLAP".equalsIgnoreCase(encryptType) && httpPort == 80) {
            return TapoAuthProtocol.KLAP;
        }
        if ("AES".equalsIgnoreCase(encryptType) && httpPort == 80) {
            return TapoAuthProtocol.AES;
        }
        return TapoAuthProtocol.UNKNOWN;
    }

    private static void validateConfiguration(TapoProperties properties) {
        if (properties.getEmail() == null || properties.getEmail().isBlank()) {
            throw new IllegalStateException("Tapo ist nicht konfiguriert: 'tapo.email' fehlt.");
        }
        if (properties.getPassword() == null || properties.getPassword().isBlank()) {
            throw new IllegalStateException("Tapo ist nicht konfiguriert: 'tapo.password' fehlt.");
        }
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record DiscoveredEndpoint(String ipAddress, TapoAuthProtocol authProtocol) {
    }

    private static String toPkcs1Pem(RSAPublicKey publicKey) throws IOException {
        byte[] modulus = derInteger(publicKey.getModulus().toByteArray());
        byte[] exponent = derInteger(publicKey.getPublicExponent().toByteArray());
        byte[] sequence = derSequence(concat(modulus, exponent));
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(sequence);
        return "-----BEGIN RSA PUBLIC KEY-----\n" + base64 + "\n-----END RSA PUBLIC KEY-----\n";
    }

    private static byte[] derSequence(byte[] content) {
        return concat(new byte[]{0x30}, derLength(content.length), content);
    }

    private static byte[] derInteger(byte[] value) {
        byte[] normalized = normalizeInteger(value);
        return concat(new byte[]{0x02}, derLength(normalized.length), normalized);
    }

    private static byte[] derLength(int length) {
        if (length < 128) {
            return new byte[]{(byte) length};
        }
        byte[] bytes = ByteBuffer.allocate(4).putInt(length).array();
        int start = 0;
        while (start < bytes.length && bytes[start] == 0) {
            start++;
        }
        byte[] result = new byte[1 + (bytes.length - start)];
        result[0] = (byte) (0x80 | (bytes.length - start));
        System.arraycopy(bytes, start, result, 1, bytes.length - start);
        return result;
    }

    private static byte[] normalizeInteger(byte[] value) {
        int start = 0;
        while (start < value.length - 1 && value[start] == 0 && (value[start + 1] & 0x80) == 0) {
            start++;
        }
        byte[] normalized = java.util.Arrays.copyOfRange(value, start, value.length);
        if ((normalized[0] & 0x80) != 0) {
            return concat(new byte[]{0}, normalized);
        }
        return normalized;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            total += array.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
