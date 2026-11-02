package com.household.manager.charging.supercharger;

import com.household.manager.charging.ChargingController;
import com.household.manager.charging.ChargingFavoriteService;
import com.household.manager.charging.ChargingPollingService;
import com.household.manager.charging.ChargingQueryService;
import com.household.manager.charging.ChargingStationDetailsService;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChargingController.class)
@AutoConfigureMockMvc(addFilters = false)
class SuperchargerControllerTest {

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
    void favoritFuerSuperchargerWirdAbgelehnt() throws Exception {
        mockMvc.perform(put("/v1/charging/favorites/sc-7")).andExpect(status().isBadRequest());

        verifyNoInteractions(favoriteService);
    }

    @Test
    void ladepunkteFuerSuperchargerWerdenAbgelehnt() throws Exception {
        mockMvc.perform(get("/v1/charging/stations/sc-7/charge-points")).andExpect(status().isBadRequest());

        verifyNoInteractions(detailsService);
    }

    @Test
    void vorhersageFuerSuperchargerWirdAbgelehnt() throws Exception {
        mockMvc.perform(get("/v1/charging/stations/sc-7/forecast")).andExpect(status().isBadRequest());

        verifyNoInteractions(queryService);
    }
}
