package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveTrackableDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TractiveControllerTest {

    @Mock
    private TractivePollingService pollingService;
    @Mock
    private TractiveZoneResolver zoneResolver;

    @Test
    void petsAreReturnedForTheMap() throws Exception {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                new TractivePositionDto(List.of(48.2082, 16.3738), 12.0, "GPS", 1800000000L),
                new TractiveHardwareDto(87, "NOT_CHARGING"),
                List.of(new GeoZone("Garten", 48.2082, 16.3738, 100)));
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));
        when(zoneResolver.resolve(48.2082, 16.3738, snapshot.zones())).thenReturn("Garten");

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TractiveController(pollingService, zoneResolver)).build();

        mockMvc.perform(get("/v1/tractive/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bello"))
                .andExpect(jsonPath("$[0].trackerId").value("dev-9"))
                .andExpect(jsonPath("$[0].latitude").value(48.2082))
                .andExpect(jsonPath("$[0].batteryPercent").value(87))
                .andExpect(jsonPath("$[0].charging").value(false))
                .andExpect(jsonPath("$[0].zone").value("Garten"));
    }

    @Test
    void petWithoutPositionOmitsCoordinates() throws Exception {
        var snapshot = new TractivePetSnapshot(
                new TractiveTrackableDto("trk-1", "dev-9",
                        new TractiveTrackableDto.Details("Bello", "DOG")),
                null, null, List.of());
        when(pollingService.latestSnapshots()).thenReturn(List.of(snapshot));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TractiveController(pollingService, zoneResolver)).build();

        mockMvc.perform(get("/v1/tractive/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latitude").doesNotExist())
                .andExpect(jsonPath("$[0].zone").value("unknown"));
    }
}
