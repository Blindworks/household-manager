package com.household.manager.tapo.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.tapo.exception.TapoAuthException;
import com.household.manager.tapo.exception.TapoConnectionException;
import com.household.manager.tapo.exception.TapoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * KLAP Protocol Client - Clean implementation based on PyTapo reference.
 *
 * KLAP (Key-based Local Authentication Protocol) is used by newer Tapo devices.
 * Protocol flow:
 * 1. Handshake1: Send local_seed, receive remote_seed + server_hash
 * 2. Handshake2: Send client_hash
 * 3. Derive keys from seeds
 * 4. Send encrypted requests using derived keys
 */
@Component("klapProtocolClientV2")
@RequiredArgsConstructor
@Slf4j
public class KlapProtocolClientV2 {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Perform KLAP handshake and return session.
     */
    public KlapSession handshake(String deviceIp, String username, String password) {
        try {
            // Step 1: Generate credentials hash
            byte[] localSeed = generateRandomBytes(16);
            byte[] authHash = createAuthHash(username, password);

            log.debug("Starting KLAP handshake for {}", deviceIp);

            // Step 2: Handshake1
            HandshakeResult hs1 = performHandshake1(deviceIp, localSeed, authHash);

            // Step 3: Handshake2
            performHandshake2(deviceIp, localSeed, hs1.remoteSeed, authHash, hs1.cookie);

            // Step 4: Derive encryption keys with prefixes
            byte[] key = deriveKey(localSeed, hs1.remoteSeed, authHash);
            byte[] iv = deriveIv(localSeed, hs1.remoteSeed, authHash);
            byte[] signatureKey = deriveSignatureKey(localSeed, hs1.remoteSeed, authHash);

            log.info("KLAP handshake successful for {}", deviceIp);
            return new KlapSession(deviceIp, hs1.cookie, key, iv, signatureKey, 0);

        } catch (Exception ex) {
            log.error("KLAP handshake failed for {}", deviceIp, ex);
            throw new TapoConnectionException("KLAP handshake failed", ex);
        }
    }

    /**
     * Send encrypted request using KLAP session.
     */
    public Map<String, Object> sendRequest(KlapSession session, Map<String, Object> request) {
        try {
            // Increment sequence BEFORE sending request (NodeJS does this)
            session.incrementSequence();
            int seq = session.getSequence();

            // Serialize request to JSON
            String jsonRequest = objectMapper.writeValueAsString(request);
            byte[] plaintext = jsonRequest.getBytes(StandardCharsets.UTF_8);

            log.debug("Sending KLAP request to {} (seq={}): {}", session.getDeviceIp(), seq, jsonRequest);
            log.debug("JSON bytes ({}): {}", plaintext.length, bytesToHex(plaintext));

            // Encrypt request
            byte[] ciphertext = encryptRequest(session.getKey(), session.getBaseIv(), seq, plaintext);

            // Compute signature: SHA256(sig_key + seq + ciphertext)
            byte[] signature = computeSignature(session.getSignatureKey(), seq, ciphertext);

            // Combine signature + ciphertext
            byte[] requestBody = concat(signature, ciphertext);

            log.debug("Request body breakdown: sig_len={} cipher_len={} total_len={}",
                    signature.length, ciphertext.length, requestBody.length);
            log.debug("First 16 bytes of signature: {}", bytesToHex(Arrays.copyOfRange(signature, 0, Math.min(16, signature.length))));
            log.debug("First 16 bytes of ciphertext: {}", bytesToHex(Arrays.copyOfRange(ciphertext, 0, Math.min(16, ciphertext.length))));
            log.debug("First 16 bytes of request body: {}", bytesToHex(Arrays.copyOfRange(requestBody, 0, Math.min(16, requestBody.length))));

            // Send HTTP request
            String url = String.format("http://%s/app/request?seq=%d", session.getDeviceIp(), seq);
            byte[] responseBytes = sendHttpRequest(url, requestBody, session.getSessionCookie());

            // Skip first 32 bytes (signature) and decrypt the rest
            if (responseBytes.length < 32) {
                throw new TapoException("Response too short: " + responseBytes.length);
            }
            byte[] responseCiphertext = Arrays.copyOfRange(responseBytes, 32, responseBytes.length);
            byte[] decrypted = decryptResponse(session.getKey(), session.getBaseIv(), seq, responseCiphertext);
            String responseJson = new String(decrypted, StandardCharsets.UTF_8);

            log.debug("Received KLAP response: {}", responseJson);

            // Parse response
            Map<String, Object> response = objectMapper.readValue(responseJson, MAP_TYPE);

            // Check for errors
            Integer errorCode = getInt(response, "error_code");
            if (errorCode != null && errorCode != 0) {
                throw new TapoException("Device returned error code: " + errorCode);
            }

            return response;

        } catch (TapoException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("KLAP request failed for {}", session.getDeviceIp(), ex);
            throw new TapoConnectionException("KLAP request failed", ex);
        }
    }

    // ==================== Handshake Methods ====================

    private HandshakeResult performHandshake1(String deviceIp, byte[] localSeed, byte[] authHash) {
        try {
            String url = String.format("http://%s/app/handshake1", deviceIp);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            HttpEntity<byte[]> entity = new HttpEntity<>(localSeed, headers);

            ResponseEntity<byte[]> response = restTemplate.postForEntity(url, entity, byte[].class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new TapoConnectionException("Handshake1 failed: " + response.getStatusCode());
            }

            byte[] body = response.getBody();
            if (body == null || body.length < 48) {
                throw new TapoConnectionException("Invalid handshake1 response");
            }

            // Extract remote_seed (16 bytes) and server_hash (32 bytes)
            byte[] remoteSeed = Arrays.copyOfRange(body, 0, 16);
            byte[] serverHash = Arrays.copyOfRange(body, 16, 48);

            // Verify server hash: SHA256(local_seed + remote_seed + auth_hash)
            byte[] expectedHash = sha256(concat(localSeed, remoteSeed, authHash));
            if (!Arrays.equals(serverHash, expectedHash)) {
                throw new TapoAuthException("Handshake1 server hash verification failed");
            }

            // Extract cookie
            String cookie = extractCookie(response.getHeaders());
            if (cookie == null || cookie.isEmpty()) {
                throw new TapoConnectionException("No session cookie received");
            }

            log.debug("Handshake1 successful for {}, cookie: {}", deviceIp, cookie);
            return new HandshakeResult(remoteSeed, cookie);

        } catch (Exception ex) {
            throw new TapoConnectionException("Handshake1 failed", ex);
        }
    }

    private void performHandshake2(String deviceIp, byte[] localSeed, byte[] remoteSeed,
                                   byte[] authHash, String cookie) {
        try {
            // Generate client hash: SHA256(remote_seed + local_seed + auth_hash)
            byte[] clientHash = sha256(concat(remoteSeed, localSeed, authHash));

            String url = String.format("http://%s/app/handshake2", deviceIp);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set(HttpHeaders.COOKIE, cookie);
            HttpEntity<byte[]> entity = new HttpEntity<>(clientHash, headers);

            ResponseEntity<byte[]> response = restTemplate.postForEntity(url, entity, byte[].class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new TapoAuthException("Handshake2 failed: " + response.getStatusCode());
            }

            log.debug("Handshake2 successful for {}", deviceIp);

        } catch (Exception ex) {
            throw new TapoConnectionException("Handshake2 failed", ex);
        }
    }

    // ==================== Encryption Methods ====================

    private byte[] encryptRequest(byte[] key, byte[] baseIv, int seq, byte[] plaintext) {
        try {
            byte[] iv = buildIv(baseIv, seq);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(plaintext);

            log.debug("Encrypted {} bytes to {} bytes (seq={})", plaintext.length, encrypted.length, seq);
            return encrypted;

        } catch (Exception ex) {
            throw new TapoException("Encryption failed", ex);
        }
    }

    private byte[] decryptResponse(byte[] key, byte[] baseIv, int seq, byte[] ciphertext) {
        try {
            byte[] iv = buildIv(baseIv, seq);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(ciphertext);

        } catch (Exception ex) {
            throw new TapoException("Decryption failed", ex);
        }
    }

    private byte[] buildIv(byte[] baseIv, int seq) {
        // IV = base_iv (12 bytes) + sequence (4 bytes, little-endian)
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.put(baseIv);
        buffer.putInt(seq);
        byte[] iv = buffer.array();
        log.debug("Built IV (seq={}): base={} seq_bytes={} full={}",
                seq,
                bytesToHex(baseIv),
                bytesToHex(Arrays.copyOfRange(iv, 12, 16)),
                bytesToHex(iv));
        return iv;
    }

    // ==================== Key Derivation ====================

    private byte[] deriveKey(byte[] localSeed, byte[] remoteSeed, byte[] authHash) {
        // key = SHA256("lsk" + local_seed + remote_seed + auth_hash)[:16]
        byte[] prefix = "lsk".getBytes(StandardCharsets.UTF_8);
        byte[] hash = sha256(concat(prefix, localSeed, remoteSeed, authHash));
        return Arrays.copyOfRange(hash, 0, 16);
    }

    private byte[] deriveIv(byte[] localSeed, byte[] remoteSeed, byte[] authHash) {
        // iv_seed = SHA256("iv" + local_seed + remote_seed + auth_hash)[:12]
        byte[] prefix = "iv".getBytes(StandardCharsets.UTF_8);
        byte[] hash = sha256(concat(prefix, localSeed, remoteSeed, authHash));
        return Arrays.copyOfRange(hash, 0, 12);
    }

    private byte[] deriveSignatureKey(byte[] localSeed, byte[] remoteSeed, byte[] authHash) {
        // sig_key = SHA256("ldk" + local_seed + remote_seed + auth_hash)[:28]
        byte[] prefix = "ldk".getBytes(StandardCharsets.UTF_8);
        byte[] hash = sha256(concat(prefix, localSeed, remoteSeed, authHash));
        return Arrays.copyOfRange(hash, 0, 28);
    }

    private byte[] computeSignature(byte[] signatureKey, int seq, byte[] ciphertext) {
        // signature = SHA256(sig_key + seq_bytes + ciphertext)
        // seq_bytes must be little-endian (Python uses struct.Struct("<l").pack)
        ByteBuffer seqBuffer = ByteBuffer.allocate(4);
        seqBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        seqBuffer.putInt(seq);
        byte[] seqBytes = seqBuffer.array();
        byte[] signature = sha256(concat(signatureKey, seqBytes, ciphertext));
        log.debug("Computed signature (seq={}): seq_bytes={} sig_key_len={} ciphertext_len={} signature={}",
                seq,
                bytesToHex(seqBytes),
                signatureKey.length,
                ciphertext.length,
                bytesToHex(signature));
        return signature;
    }

    private byte[] createAuthHash(String username, String password) {
        // auth_hash = SHA256(SHA1(username) + SHA1(password))
        byte[] usernameHash = sha1(username.getBytes(StandardCharsets.UTF_8));
        byte[] passwordHash = sha1(password.getBytes(StandardCharsets.UTF_8));
        return sha256(concat(usernameHash, passwordHash));
    }

    // ==================== HTTP Methods ====================

    private byte[] sendHttpRequest(String url, byte[] body, String cookie) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set(HttpHeaders.COOKIE, cookie);

            HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);

            log.debug("Sending HTTP POST to {} with {} bytes, cookie: {}", url, body.length, cookie);

            ResponseEntity<byte[]> response = restTemplate.postForEntity(url, entity, byte[].class);

            log.debug("Received HTTP response: {}, body: {} bytes",
                     response.getStatusCode(),
                     response.getBody() != null ? response.getBody().length : 0);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new TapoConnectionException("HTTP request failed: " + response.getStatusCode());
            }

            return response.getBody();

        } catch (TapoConnectionException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("HTTP request to {} failed", url, ex);
            throw new TapoConnectionException("HTTP request failed: " + ex.getMessage(), ex);
        }
    }

    private String extractCookie(HttpHeaders headers) {
        List<String> cookies = headers.get(HttpHeaders.SET_COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }

        // Return the first cookie, removing parameters after semicolon
        String cookie = cookies.get(0);
        int semiIndex = cookie.indexOf(';');
        return semiIndex > 0 ? cookie.substring(0, semiIndex) : cookie;
    }

    // ==================== Crypto Utilities ====================

    private byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception ex) {
            throw new TapoException("SHA-256 failed", ex);
        }
    }

    private byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception ex) {
            throw new TapoException("SHA-1 failed", ex);
        }
    }

    private byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private byte[] concat(byte[]... arrays) {
        int totalLength = Arrays.stream(arrays).mapToInt(a -> a.length).sum();
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ==================== Inner Classes ====================

    private record HandshakeResult(byte[] remoteSeed, String cookie) {}
}
