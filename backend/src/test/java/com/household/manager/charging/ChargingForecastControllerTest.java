package com.household.manager.charging;

import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChargingController.class)
@AutoConfigureMockMvc(addFilters = false)
class ChargingForecastControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChargingQueryService queryService;
    @MockitoBean
    private ChargingPollingService pollingService;
    @MockitoBean
    private ChargingFavoriteService favoriteService;
    @MockitoBean
    private ChargingStationDetailsService detailsService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    @Test
    void liefertVorhersageMitSiebenMalVierundzwanzigHeatmap() throws Exception {
        Double[][] heatmap = new Double[7][24];
        heatmap[0][0] = 0.5;
        when(queryService.forecast("fav")).thenReturn(new ChargingDtos.ForecastResponse("fav", "OK", 20,
                Map.of(15, 40, 30, 62, 60, 80),
                List.of(new ChargingDtos.PointForecastResponse("P1", LocalDateTime.of(2026, 9, 19, 11, 0),
                        LocalDateTime.of(2026, 9, 19, 11, 30), LocalDateTime.of(2026, 9, 19, 12, 0))),
                heatmap));

        mockMvc.perform(get("/v1/charging/stations/fav/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value("fav"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.freeProbabilityPercent.30").value(62))
                .andExpect(jsonPath("$.points[0].chargePointId").value("P1"))
                .andExpect(jsonPath("$.heatmap.length()").value(7))
                .andExpect(jsonPath("$.heatmap[0].length()").value(24))
                .andExpect(jsonPath("$.heatmap[0][0]").value(0.5));
    }
}
