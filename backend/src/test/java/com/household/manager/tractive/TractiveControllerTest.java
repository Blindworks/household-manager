package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePetDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TractiveControllerTest {

    @Mock
    private TractivePetService petService;

    @Test
    void petsDelegatesToTheService() throws Exception {
        var pet = new TractivePetDto("dev-9", "Bello", 48.2082, 16.3738, 12.0, "GPS",
                Instant.ofEpochSecond(1800000000L), 87, false, "Garten", true);
        when(petService.listPets()).thenReturn(List.of(pet));

        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new TractiveController(petService)).build();

        mockMvc.perform(get("/v1/tractive/pets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bello"))
                .andExpect(jsonPath("$[0].trackerId").value("dev-9"))
                .andExpect(jsonPath("$[0].latitude").value(48.2082))
                .andExpect(jsonPath("$[0].batteryPercent").value(87))
                .andExpect(jsonPath("$[0].charging").value(false))
                .andExpect(jsonPath("$[0].zone").value("Garten"))
                .andExpect(jsonPath("$[0].atHome").value(true));
    }
}
