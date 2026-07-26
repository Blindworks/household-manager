package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TractivePetServiceTest {

    @Mock
    private TractivePollingService pollingService;
    @Mock
    private TractiveZoneResolver zoneResolver;

    @Test
    void petsAreReturnedForTheMap() {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L),
                new TractiveHardwareDto(87, "NOT_CHARGING"),
                List.of(new GeoZone("Garten", 48.2082, 16.3738, 100)));
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));
        when(zoneResolver.resolve(48.2082, 16.3738, snapshot.zones())).thenReturn("Garten");

        List<TractivePetDto> pets = new TractivePetService(pollingService, zoneResolver).listPets();

        assertThat(pets).hasSize(1);
        TractivePetDto pet = pets.get(0);
        assertThat(pet.name()).isEqualTo("Bello");
        assertThat(pet.trackerId()).isEqualTo("dev-9");
        assertThat(pet.latitude()).isEqualTo(48.2082);
        assertThat(pet.batteryPercent()).isEqualTo(87);
        assertThat(pet.charging()).isFalse();
        assertThat(pet.zone()).isEqualTo("Garten");
    }

    @Test
    void petWithoutPositionOmitsCoordinates() {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                null, null, List.of());
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));

        List<TractivePetDto> pets = new TractivePetService(pollingService, zoneResolver).listPets();

        assertThat(pets).hasSize(1);
        TractivePetDto pet = pets.get(0);
        assertThat(pet.latitude()).isNull();
        assertThat(pet.longitude()).isNull();
        assertThat(pet.zone()).isEqualTo(TractiveZoneResolver.UNKNOWN);
    }

    @Test
    void emptyCacheYieldsEmptyList() {
        when(pollingService.latestSnapshots()).thenReturn(List.of());

        List<TractivePetDto> pets = new TractivePetService(pollingService, zoneResolver).listPets();

        assertThat(pets).isEmpty();
    }
}
