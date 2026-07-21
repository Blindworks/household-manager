package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiSmartlockDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Dünner HTTP-Client für die Nuki Web API (Bearer-Auth).
 * Action-Codes: 1 = entsperren, 2 = verriegeln, 3 = Tür öffnen.
 */
@Component
@Slf4j
public class NukiApiClient {

    private final NukiProperties properties;
    private final RestTemplate restTemplate;

    public NukiApiClient(NukiProperties properties, RestTemplateBuilder builder) {
        this.properties = properties;
        this.restTemplate = builder
                .connectTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getHttpTimeoutMs()))
                .build();
    }

    public List<NukiSmartlockDto> listSmartlocks() {
        String url = properties.getBaseUrl() + "/smartlock";
        try {
            var response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders()),
                    new ParameterizedTypeReference<List<NukiSmartlockDto>>() {
                    });
            return response.getBody() != null ? response.getBody() : List.of();
        } catch (RestClientException ex) {
            throw new NukiException("Nuki Web API nicht erreichbar: " + ex.getMessage(), ex);
        }
    }

    public void sendAction(long smartlockId, int actionCode) {
        String url = properties.getBaseUrl() + "/smartlock/" + smartlockId + "/action";
        try {
            HttpHeaders headers = authHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(Map.of("action", actionCode), headers), Void.class);
        } catch (RestClientException ex) {
            throw new NukiException("Nuki-Aktion fehlgeschlagen: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiToken());
        return headers;
    }

    /** Nur für Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }
}
