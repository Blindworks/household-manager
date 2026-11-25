package com.household.manager.communicator;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;

/**
 * Wandelt eine Sprachaufnahme in Text um. Spricht das OpenAI-Format
 * ({@code POST <base>/v1/audio/transcriptions}, multipart) — dasselbe Format bedienen
 * lokale Whisper-Server, deshalb entscheidet allein die Konfiguration, wohin der Ton geht.
 */
@Component
public class SpeechToTextClient {

    /** Ein Modell-Download (small: rund 500 MB) dauert je nach Leitung Minuten. */
    static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);
    static final String MODEL_MISSING_TEXT =
            "Das Sprachmodell wird noch geladen. Bitte in ein paar Minuten noch einmal versuchen.";

    private final CommunicatorProperties.Stt properties;
    private final RestTemplate restTemplate;
    private final RestTemplate installRestTemplate;
    private final RestTemplate healthRestTemplate;

    public SpeechToTextClient(CommunicatorProperties properties, RestTemplateBuilder builder) {
        this.properties = properties.getStt();
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofMillis(this.properties.getTimeoutMs()))
                .build();
        this.installRestTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(INSTALL_TIMEOUT)
                .build();
        this.healthRestTemplate = builder
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Laedt das konfigurierte Modell auf dem speaches-Server nach; ist es schon da, antwortet der Server
     * sofort. Blockiert bis zum Ende des Downloads — nie auf einem Request-Thread aufrufen.
     *
     * @throws SpeechToTextException wenn der Server nicht antwortet oder den Download ablehnt
     */
    public void installModel() {
        try {
            // Die Modell-Id enthaelt einen Schraegstrich und gehoert unveraendert in den Pfad.
            installRestTemplate.postForObject(
                    URI.create(stripTrailingSlash(properties.getBaseUrl()) + "/v1/models/" + properties.getModel()),
                    null, String.class);
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new SpeechToTextException("Das Sprachmodell konnte nicht geladen werden.", ex);
        }
    }

    /**
     * Fragt die installierten Modelle ab (OpenAI-Format {@code GET /v1/models}).
     *
     * @return ob das konfigurierte Modell dabei ist
     * @throws SpeechToTextException wenn der Dienst nicht antwortet
     */
    public boolean isModelInstalled() {
        HttpHeaders headers = new HttpHeaders();
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.setBearerAuth(properties.getApiKey());
        }
        try {
            JsonNode root = healthRestTemplate.exchange(stripTrailingSlash(properties.getBaseUrl()) + "/v1/models",
                    HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
            if (root != null) {
                for (JsonNode model : root.path("data")) {
                    if (properties.getModel().equals(model.path("id").asText())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new SpeechToTextException("Die Spracherkennung ist nicht erreichbar: " + ex.getMessage(), ex);
        }
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * @return der erkannte Text, leer wenn nichts verstanden wurde
     * @throws SpeechToTextException wenn der Dienst nicht eingerichtet ist oder nicht antwortet
     */
    public String transcribe(byte[] audio, String contentType) {
        if (!isConfigured()) {
            throw new SpeechToTextException("Die Spracherkennung ist nicht eingerichtet.", null);
        }
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", filePart(audio, contentType));
        form.add("model", properties.getModel());
        form.add("language", properties.getLanguage());
        form.add("response_format", "json");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.setBearerAuth(properties.getApiKey());
        }
        try {
            JsonNode root = restTemplate.postForObject(
                    stripTrailingSlash(properties.getBaseUrl()) + "/v1/audio/transcriptions",
                    new HttpEntity<>(form, headers), JsonNode.class);
            return root == null ? "" : root.path("text").asText("").trim();
        } catch (HttpClientErrorException.NotFound ex) {
            // speaches meldet ein nicht installiertes Modell mit 404.
            throw new SpeechModelMissingException(MODEL_MISSING_TEXT, ex);
        } catch (RestClientException ex) {
            throw new SpeechToTextException("Die Spracherkennung ist nicht erreichbar.", ex);
        }
    }

    private static HttpEntity<ByteArrayResource> filePart(byte[] audio, String contentType) {
        String fileName = fileNameFor(contentType);
        ByteArrayResource resource = new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(parseOrDefault(contentType));
        return new HttpEntity<>(resource, partHeaders);
    }

    /** Whisper-Dienste erkennen das Format an der Dateiendung; iOS nimmt MP4/AAC auf, Chrome WebM. */
    static String fileNameFor(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("mp4") || type.contains("m4a") || type.contains("aac")) {
            return "audio.m4a";
        }
        if (type.contains("ogg")) {
            return "audio.ogg";
        }
        if (type.contains("wav")) {
            return "audio.wav";
        }
        if (type.contains("mpeg") || type.contains("mp3")) {
            return "audio.mp3";
        }
        return "audio.webm";
    }

    private static MediaType parseOrDefault(String contentType) {
        try {
            return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        } catch (RuntimeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Nur fuer Tests (MockRestServiceServer). */
    RestTemplate restTemplate() {
        return restTemplate;
    }

    /** Nur fuer Tests (MockRestServiceServer). */
    RestTemplate healthRestTemplate() {
        return healthRestTemplate;
    }

    /** Nur fuer Tests (MockRestServiceServer). */
    RestTemplate installRestTemplate() {
        return installRestTemplate;
    }

    /** Spracherkennung nicht eingerichtet oder nicht erreichbar. */
    public static class SpeechToTextException extends RuntimeException {
        public SpeechToTextException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Der Dienst laeuft, kennt das konfigurierte Modell aber (noch) nicht. */
    public static class SpeechModelMissingException extends SpeechToTextException {
        public SpeechModelMissingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
