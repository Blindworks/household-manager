package com.household.manager.house;

import com.household.manager.model.entity.HouseOpeningHinge;
import com.household.manager.model.entity.HouseOpeningKind;
import com.household.manager.model.entity.HouseOpeningSize;
import com.household.manager.model.entity.HouseOpeningSwing;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HouseOpeningController.class)
@AutoConfigureMockMvc(addFilters = false)
class HouseOpeningControllerTest {

    private static final String BODY = "{\"floor\":\"ground\",\"kind\":\"WINDOW\",\"size\":\"NORMAL\","
            + "\"x1\":60,\"y1\":90,\"x2\":100,\"y2\":90,\"hinge\":\"START\",\"swing\":\"POSITIVE\","
            + "\"deviceId\":null}";

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private HouseOpeningService service;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    private static HouseOpeningDtos.Response response(long id) {
        return new HouseOpeningDtos.Response(id, "ground", HouseOpeningKind.WINDOW, HouseOpeningSize.NORMAL,
                60.0, 90.0, 100.0, 90.0, HouseOpeningHinge.START, HouseOpeningSwing.POSITIVE, null);
    }

    @Test
    void getLiefertDieListe() throws Exception {
        when(service.list()).thenReturn(List.of(response(1)));
        mockMvc.perform(get("/v1/house/openings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].kind").value("WINDOW"))
                .andExpect(jsonPath("$[0].x1").value(60.0));
    }

    @Test
    void postLiefert201() throws Exception {
        when(service.create(any())).thenReturn(response(5));
        mockMvc.perform(post("/v1/house/openings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void putLiefert200() throws Exception {
        when(service.update(eq(5L), any())).thenReturn(response(5));
        mockMvc.perform(put("/v1/house/openings/5").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void deleteLiefert204() throws Exception {
        mockMvc.perform(delete("/v1/house/openings/5")).andExpect(status().isNoContent());
        verify(service).delete(5L);
    }

    @Test
    void ungueltigeWerteErgeben400() throws Exception {
        when(service.create(any())).thenThrow(new IllegalArgumentException("ungueltig"));
        mockMvc.perform(post("/v1/house/openings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/house/openings")).andExpect(status().isBadRequest());
    }
}
