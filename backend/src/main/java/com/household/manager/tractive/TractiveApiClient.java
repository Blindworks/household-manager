package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveGeofenceDto;
import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTokenDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import com.household.manager.tractive.dto.TractiveTrackableRefDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
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

    public List<TractiveTrackableRefDto> listTrackableObjects(String token, String userId) {
        return getList("/user/" + userId + "/trackable_objects", token, userId,
                new ParameterizedTypeReference<List<TractiveTrackableRefDto>>() {
                });
    }

    public TractiveTrackableDto getTrackable(String token, String userId, String trackableId) {
        return get("/trackable_object/" + trackableId, token, userId, TractiveTrackableDto.class);
    }

    public TractivePositionDto getPosition(String token, String userId, String trackerId) {
        return get("/device_pos_report/" + trackerId, token, userId, TractivePositionDto.class);
    }

    public TractiveHardwareDto getHardware(String token, String userId, String trackerId) {
        // Der abschliessende Slash ist von der API vorgegeben.
        return get("/device_hw_report/" + trackerId + "/", token, userId, TractiveHardwareDto.class);
    }

    /**
     * Virtual Fences des Trackers. Fehler werden geschluckt: die Zonen sind
     * eine Verbesserung, ihr Fehlen darf den Poll-Zyklus nicht kippen.
     */
    public List<TractiveGeofenceDto> listGeofences(String token, String userId, String trackerId) {
        try {
            return getList("/tracker/" + trackerId + "/geofences", token, userId,
                    new ParameterizedTypeReference<List<TractiveGeofenceDto>>() {
                    });
        } catch (TractiveException ex) {
            log.warn("Tractive-Geofences nicht lesbar ({}), es gilt die Home-Zone", ex.getMessage());
            return List.of();
        }
    }

    private <T> T get(String path, String token, String userId, Class<T> type) {
        try {
            var response = restTemplate.exchange(properties.getBaseUrl() + path, HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token, userId)), type);
            T body = response.getBody();
            if (body == null) {
                throw new TractiveException("Leere Antwort von " + path);
            }
            return body;
        } catch (RestClientException ex) {
            throw new TractiveException("Tractive-Abruf " + path + " fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private <T> List<T> getList(String path, String token, String userId,
                                ParameterizedTypeReference<List<T>> type) {
        try {
            var response = restTemplate.exchange(properties.getBaseUrl() + path, HttpMethod.GET,
                    new HttpEntity<>(authHeaders(token, userId)), type);
            return response.getBody() != null ? response.getBody() : List.of();
        } catch (RestClientException ex) {
            throw new TractiveException("Tractive-Abruf " + path + " fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders authHeaders(String token, String userId) {
        HttpHeaders headers = baseHeaders();
        headers.setBearerAuth(token);
        headers.set("x-tractive-user", userId);
        return headers;
    }

    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-tractive-client", properties.getClientId());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    /** Nur fuer Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
