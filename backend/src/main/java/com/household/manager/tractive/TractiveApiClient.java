package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveTokenDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * Duenner HTTP-Client fuer die inoffizielle Tractive-API.
 * Enthaelt ausschliesslich Transport-Logik; Zugangsdaten werden nie geloggt.
 */
@Component
@Slf4j
public class TractiveApiClient {

    private final TractiveProperties properties;
    private final RestTemplate restTemplate;

    public TractiveApiClient(TractiveProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .build();
    }

    public TractiveTokenDto login(String email, String password) {
        HttpHeaders headers = baseHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
                "platform_email", email,
                "platform_token", password,
                "grant_type", "tractive");
        try {
            var response = restTemplate.exchange(properties.getBaseUrl() + "/auth/token",
                    HttpMethod.POST, new HttpEntity<>(body, headers), TractiveTokenDto.class);
            TractiveTokenDto token = response.getBody();
            if (token == null || token.accessToken() == null) {
                throw new TractiveException("Tractive-Login lieferte kein Token");
            }
            return token;
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            // Bewusst ohne Zugangsdaten im Log.
            throw new TractiveAuthException("Anmeldung bei Tractive fehlgeschlagen.");
        } catch (RestClientException ex) {
            // Bewusst ohne Zugangsdaten im Log.
            throw new TractiveException("Tractive-Login fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-tractive-client", properties.getClientId());
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    /** Nur fuer Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
