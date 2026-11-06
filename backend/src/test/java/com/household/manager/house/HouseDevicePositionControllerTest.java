package com.household.manager.house;

import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HouseDevicePositionController.class)
@AutoConfigureMockMvc(addFilters = false)
class HouseDevicePositionControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private HouseDevicePositionService service;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    @Test
    void getLiefertDieListe() throws Exception {
        when(service.list()).thenReturn(List.of(new HouseDevicePositionDtos.Response("a", 1.5, 2.0)));

        mockMvc.perform(get("/v1/house/device-positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value("a"))
                .andExpect(jsonPath("$[0].x").value(1.5))
                .andExpect(jsonPath("$[0].y").value(2.0));
    }

    @Test
    void putRuftSetMitPfadIdUndWerten() throws Exception {
        when(service.set("a", 10.0, 20.0)).thenReturn(new HouseDevicePositionDtos.Response("a", 10.0, 20.0));

        mockMvc.perform(put("/v1/house/device-positions/a").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":10,\"y\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("a"))
                .andExpect(jsonPath("$.x").value(10.0));
        verify(service).set("a", 10.0, 20.0);
    }

    @Test
    void putOhneBodyOderMitNullErgibt400() throws Exception {
        when(service.set("a", null, 1.0)).thenThrow(new IllegalArgumentException("x fehlt"));

        mockMvc.perform(put("/v1/house/device-positions/a")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/house/device-positions/a").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":null,\"y\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ungueltigeWerteAusDemServiceErgeben400() throws Exception {
        when(service.set("A_b", 1.0, 1.0)).thenThrow(new IllegalArgumentException("ungueltig"));

        mockMvc.perform(put("/v1/house/device-positions/A_b").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\":1,\"y\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteLiefert204() throws Exception {
        mockMvc.perform(delete("/v1/house/device-positions/a")).andExpect(status().isNoContent());
        verify(service).delete("a");
    }
}
