package com.household.manager.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.VisionPerson;
import com.household.manager.model.entity.VisionPersonPhoto;
import com.household.manager.repository.VisionPersonPhotoRepository;
import com.household.manager.repository.VisionPersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisionPersonServiceTest {

    @Mock
    private VisionPersonRepository personRepository;
    @Mock
    private VisionPersonPhotoRepository photoRepository;
    @Mock
    private VisionSidecarClient sidecarClient;

    private VisionPersonService service;

    @BeforeEach
    void setUp() {
        service = new VisionPersonService(personRepository, photoRepository, sidecarClient, new ObjectMapper());
    }

    @Test
    void addPhotoComputesEmbeddingAndPushesPersons() {
        VisionPerson person = VisionPerson.builder().id(1L).name("Benedikt").active(true).build();
        when(personRepository.findById(1L)).thenReturn(Optional.of(person));
        when(sidecarClient.computeEmbedding(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(photoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(personRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(person));
        when(photoRepository.findByPersonId(1L)).thenReturn(List.of(
                VisionPersonPhoto.builder().personId(1L).embedding("[0.1,0.2]").build()));

        service.addPhoto(1L, new byte[]{1, 2, 3});

        ArgumentCaptor<VisionPersonPhoto> captor = ArgumentCaptor.forClass(VisionPersonPhoto.class);
        verify(photoRepository).save(captor.capture());
        assertThat(captor.getValue().getEmbedding()).isEqualTo("[0.1,0.2]");
        verify(sidecarClient).pushPersons(anyList());
    }

    @Test
    void addPhotoForUnknownPersonThrows() {
        when(personRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(VisionException.class, () -> service.addPhoto(99L, new byte[]{1}));
        verifyNoInteractions(sidecarClient);
    }

    @Test
    void pushFailureDoesNotRollBackPhoto() {
        VisionPerson person = VisionPerson.builder().id(1L).name("Benedikt").active(true).build();
        when(personRepository.findById(1L)).thenReturn(Optional.of(person));
        when(sidecarClient.computeEmbedding(any())).thenReturn(new float[]{0.1f});
        when(photoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(personRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(person));
        when(photoRepository.findByPersonId(1L)).thenReturn(List.of());
        doThrow(new VisionException("Sidecar weg")).when(sidecarClient).pushPersons(anyList());

        service.addPhoto(1L, new byte[]{1});  // darf NICHT werfen

        verify(photoRepository).save(any());
    }

    @Test
    void deletePersonRemovesPhotosAndPushes() {
        when(personRepository.existsById(1L)).thenReturn(true);
        when(personRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of());

        service.deletePerson(1L);

        verify(photoRepository).deleteByPersonId(1L);
        verify(personRepository).deleteById(1L);
        verify(sidecarClient).pushPersons(anyList());
    }

    @Test
    void embeddingsPayloadSkipsUnparseableEmbedding() {
        VisionPerson person = VisionPerson.builder().id(1L).name("Benedikt").active(true).build();
        when(personRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(person));
        when(photoRepository.findByPersonId(1L)).thenReturn(List.of(
                VisionPersonPhoto.builder().personId(1L).embedding("kaputt").build(),
                VisionPersonPhoto.builder().personId(1L).embedding("[0.5]").build()));

        List<VisionSidecarClient.SidecarPerson> payload = service.buildSidecarPersons();

        assertThat(payload).hasSize(1);
        assertThat(payload.get(0).embeddings()).hasSize(1);
        assertThat(payload.get(0).embeddings().get(0)).containsExactly(0.5f);
    }
}
