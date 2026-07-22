package com.household.manager.controller;

import com.household.manager.dto.VisionDtos.*;
import com.household.manager.vision.VisionPersonService;
import com.household.manager.vision.VisionRecognitionService;
import com.household.manager.vision.VisionSidecarClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** REST-API der Blink-Gesichtserkennung (Frontend + Sidecar-Webhooks). */
@RestController
@RequestMapping("/v1/vision")
@RequiredArgsConstructor
@Slf4j
public class VisionController {

    /** Verhindert, dass ein unbedachtes ?limit=100000 sehr viele Thumbnails auf einmal laedt. */
    private static final int MAX_RECOGNITIONS_LIMIT = 200;

    private final VisionPersonService personService;
    private final VisionRecognitionService recognitionService;
    private final VisionSidecarClient sidecarClient;

    // ==================== Frontend ====================

    @GetMapping("/persons")
    public List<PersonResponse> getPersons() {
        return personService.getAll().stream()
                .map(p -> new PersonResponse(p.getId(), p.getName(), p.isActive(),
                        (int) personService.countPhotos(p.getId())))
                .toList();
    }

    @PostMapping("/persons")
    @ResponseStatus(HttpStatus.CREATED)
    public PersonResponse createPerson(@RequestBody PersonRequest request) {
        var person = personService.createPerson(request.name());
        return new PersonResponse(person.getId(), person.getName(), person.isActive(), 0);
    }

    @PutMapping("/persons/{id}")
    public PersonResponse updatePerson(@PathVariable Long id, @RequestBody PersonRequest request) {
        var person = personService.updatePerson(id, request.name(),
                request.active() == null || request.active());
        return new PersonResponse(person.getId(), person.getName(), person.isActive(),
                (int) personService.countPhotos(id));
    }

    @DeleteMapping("/persons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePerson(@PathVariable Long id) {
        personService.deletePerson(id);
    }

    @GetMapping("/persons/{id}/photos")
    public List<PhotoResponse> getPhotos(@PathVariable Long id) {
        return personService.getPhotos(id).stream()
                .map(photo -> new PhotoResponse(photo.getId(), photo.getPersonId(),
                        Base64.getEncoder().encodeToString(photo.getPhoto())))
                .toList();
    }

    @PostMapping("/persons/{id}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoResponse addPhoto(@PathVariable Long id, @RequestParam("file") MultipartFile file)
            throws IOException {
        var photo = personService.addPhoto(id, file.getBytes());
        return new PhotoResponse(photo.getId(), photo.getPersonId(),
                Base64.getEncoder().encodeToString(photo.getPhoto()));
    }

    @DeleteMapping("/persons/{personId}/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@PathVariable Long personId, @PathVariable Long photoId) {
        personService.deletePhoto(photoId);
    }

    @GetMapping("/recognitions")
    public List<RecognitionResponse> getRecognitions(@RequestParam(defaultValue = "50") int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_RECOGNITIONS_LIMIT));
        return recognitionService.getRecent(boundedLimit).stream()
                .map(r -> new RecognitionResponse(r.getId(), r.getRecognizedAt(), r.getPersonId(),
                        r.getPersonName(), r.getConfidence(), r.getUnknownFaces(),
                        r.getThumbnail() == null ? null : Base64.getEncoder().encodeToString(r.getThumbnail())))
                .toList();
    }

    @GetMapping("/status")
    public StatusResponse getStatus() {
        try {
            var status = sidecarClient.getStatus();
            return new StatusResponse(true, status.loggedIn(), status.cameraFound(),
                    status.cameraName(), status.lastPollAt());
        } catch (Exception ex) {
            log.warn("blink-vision-Status nicht abrufbar: {}", ex.getMessage());
            return new StatusResponse(false, false, false, null, null);
        }
    }

    @PostMapping("/auth/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(@RequestBody LoginRequest request) {
        sidecarClient.login(request.username(), request.password());
    }

    @PostMapping("/auth/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@RequestBody VerifyRequest request) {
        sidecarClient.verify(request.code());
    }

    // ==================== Sidecar -> Backend ====================

    @PostMapping("/recognitions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportRecognition(@RequestBody RecognitionWebhook webhook) {
        List<VisionRecognitionService.RecognizedPerson> persons =
                Optional.ofNullable(webhook.persons()).orElse(List.of()).stream()
                        .map(p -> new VisionRecognitionService.RecognizedPerson(
                                p.personId(), p.name(),
                                p.confidence() == null ? BigDecimal.ZERO : p.confidence()))
                        .toList();
        byte[] thumbnail = webhook.thumbnailBase64() == null
                ? null : Base64.getDecoder().decode(webhook.thumbnailBase64());
        recognitionService.processRecognition(new VisionRecognitionService.RecognitionReport(
                persons, Optional.ofNullable(webhook.unknownFaces()).orElse(0), thumbnail));
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat() {
        recognitionService.heartbeat();
    }

    @GetMapping("/embeddings")
    public List<EmbeddingsPerson> getEmbeddings() {
        return personService.buildSidecarPersons().stream()
                .map(p -> new EmbeddingsPerson(p.personId(), p.name(), p.embeddings()))
                .toList();
    }
}
