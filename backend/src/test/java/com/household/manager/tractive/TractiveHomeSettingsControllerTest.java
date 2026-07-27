package com.household.manager.tractive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TractiveHomeSettingsControllerTest {

    @Mock
    private TractiveHomeSettingsService settingsService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TractiveHomeSettingsController(settingsService))
                .build();
    }

    private String body(Double latitude, Double longitude, double radius, double arrivalRadius,
                        long minutes, int battery, String zoneName) throws Exception {
        return objectMapper.writeValueAsString(new TractiveHomeSettings(
                latitude, longitude, radius, arrivalRadius, minutes, battery, zoneName));
    }

    @Test
    void getReturnsTheStoredSettings() throws Exception {
        when(settingsService.getSettings()).thenReturn(new TractiveHomeSettings(
                48.2082, 16.3738, 100, 500, 60, 15, "Zuhause"));

        mockMvc.perform(get("/v1/tractive/home-settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homeLatitude").value(48.2082))
                .andExpect(jsonPath("$.homeZoneName").value("Zuhause"));
    }

    @Test
    void validSettingsAreSaved() throws Exception {
        when(settingsService.getSettings()).thenReturn(new TractiveHomeSettings(
                48.2082, 16.3738, 120, 400, 45, 20, "Daheim"));

        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 16.3738, 120, 400, 45, 20, "Daheim")))
                .andExpect(status().isOk());

        verify(settingsService).saveSettings(new TractiveHomeSettings(
                48.2082, 16.3738, 120, 400, 45, 20, "Daheim"));
    }

    /** Koordinaten duerfen geleert werden – das Feature wird damit bewusst abgeschaltet. */
    @Test
    void emptyCoordinatesAreAllowed() throws Exception {
        when(settingsService.getSettings()).thenReturn(new TractiveHomeSettings(
                null, null, 100, 500, 60, 15, "Zuhause"));

        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, null, 100, 500, 60, 15, "Zuhause")))
                .andExpect(status().isOk());
    }

    @Test
    void anImpossibleLatitudeIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(480.0, 16.3738, 100, 500, 60, 15, "Zuhause")))
                .andExpect(status().isBadRequest());

        verify(settingsService, never()).saveSettings(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void anImpossibleLongitudeIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 200.0, 100, 500, 60, 15, "Zuhause")))
                .andExpect(status().isBadRequest());
    }

    /** Eine halbe Koordinate ist keine Position und wuerde still als "nicht konfiguriert" enden. */
    @Test
    void halfACoordinateIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, null, 100, 500, 60, 15, "Zuhause")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aZeroRadiusIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 16.3738, 0, 500, 60, 15, "Zuhause")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aZeroSilenceThresholdIsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 16.3738, 100, 500, 0, 15, "Zuhause")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBatteryPercentAbove100IsRejected() throws Exception {
        mockMvc.perform(put("/v1/tractive/home-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(48.2082, 16.3738, 100, 500, 60, 150, "Zuhause")))
                .andExpect(status().isBadRequest());
    }
}
