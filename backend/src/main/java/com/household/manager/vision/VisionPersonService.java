package com.household.manager.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.VisionPerson;
import com.household.manager.model.entity.VisionPersonPhoto;
import com.household.manager.repository.VisionPersonPhotoRepository;
import com.household.manager.repository.VisionPersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Verwaltung der Bewohner und Referenzfotos. Das Backend ist fuehrend;
 * der Sidecar bekommt nach jeder Aenderung die komplette Embedding-Liste gepusht
 * (best effort — ein nicht erreichbarer Sidecar blockiert keine Verwaltung,
 * er holt sich die Liste beim Start selbst ueber GET /v1/vision/embeddings).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisionPersonService {

    private final VisionPersonRepository personRepository;
    private final VisionPersonPhotoRepository photoRepository;
    private final VisionSidecarClient sidecarClient;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<VisionPerson> getAll() {
        return personRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public List<VisionPersonPhoto> getPhotos(Long personId) {
        return photoRepository.findByPersonId(personId);
    }

    @Transactional
    public VisionPerson createPerson(String name) {
        VisionPerson person = personRepository.save(
                VisionPerson.builder().name(name.trim()).active(true).build());
        pushPersonsSafely();
        return person;
    }

    @Transactional
    public VisionPerson updatePerson(Long id, String name, boolean active) {
        VisionPerson person = personRepository.findById(id)
                .orElseThrow(() -> new VisionException("Person " + id + " ist unbekannt."));
        person.setName(name.trim());
        person.setActive(active);
        VisionPerson saved = personRepository.save(person);
        pushPersonsSafely();
        return saved;
    }

    @Transactional
    public void deletePerson(Long id) {
        if (!personRepository.existsById(id)) {
            throw new VisionException("Person " + id + " ist unbekannt.");
        }
        photoRepository.deleteByPersonId(id);
        personRepository.deleteById(id);
        pushPersonsSafely();
    }

    /** Foto speichern: Sidecar berechnet das Embedding, beides wird persistiert. */
    @Transactional
    public VisionPersonPhoto addPhoto(Long personId, byte[] photo) {
        personRepository.findById(personId)
                .orElseThrow(() -> new VisionException("Person " + personId + " ist unbekannt."));
        float[] embedding = sidecarClient.computeEmbedding(photo);
        VisionPersonPhoto saved = photoRepository.save(VisionPersonPhoto.builder()
                .personId(personId)
                .photo(photo)
                .embedding(toJson(embedding))
                .build());
        pushPersonsSafely();
        return saved;
    }

    @Transactional
    public void deletePhoto(Long photoId) {
        photoRepository.deleteById(photoId);
        pushPersonsSafely();
    }

    /** Personen + Embeddings im Sidecar-Format (auch fuer GET /v1/vision/embeddings). */
    @Transactional(readOnly = true)
    public List<VisionSidecarClient.SidecarPerson> buildSidecarPersons() {
        List<VisionSidecarClient.SidecarPerson> result = new ArrayList<>();
        for (VisionPerson person : personRepository.findByActiveTrueOrderByNameAsc()) {
            List<float[]> embeddings = new ArrayList<>();
            for (VisionPersonPhoto photo : photoRepository.findByPersonId(person.getId())) {
                float[] embedding = fromJson(photo.getEmbedding());
                if (embedding != null) {
                    embeddings.add(embedding);
                }
            }
            result.add(new VisionSidecarClient.SidecarPerson(person.getId(), person.getName(), embeddings));
        }
        return result;
    }

    private void pushPersonsSafely() {
        try {
            sidecarClient.pushPersons(buildSidecarPersons());
        } catch (Exception ex) {
            log.warn("Embedding-Push an blink-vision fehlgeschlagen (Sidecar holt sie beim Start selbst): {}",
                    ex.getMessage());
        }
    }

    private String toJson(float[] embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception ex) {
            throw new VisionException("Embedding konnte nicht serialisiert werden.", ex);
        }
    }

    private float[] fromJson(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (Exception ex) {
            log.warn("Unlesbares Embedding wird uebersprungen: {}", ex.getMessage());
            return null;
        }
    }
}
