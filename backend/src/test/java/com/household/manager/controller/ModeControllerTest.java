package com.household.manager.controller;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Haelt die tatsaechliche JSON-Serialisierung der Modus-API fest, nicht nur das
 * Java-Objekt: Das Angular-Frontend liest {@code quickAccess} als konkreten
 * Feldnamen aus der Antwort. Ein Tippfehler im Record-Komponentennamen oder ein
 * kuenftig ergaenztes {@code @JsonProperty} wuerde den Vertrag brechen, ohne dass
 * ein Test, der nur gegen {@link ModeResponse} deserialisiert, das je bemerkt -
 * deshalb pruefen die Tests hier ausschliesslich per {@code jsonPath} gegen den
 * rohen JSON-Text.
 */
@WebMvcTest(controllers = ModeController.class)
@AutoConfigureMockMvc(addFilters = false)
class ModeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HouseModeQueryService houseModeQueryService;
    @MockitoBean
    private ManualEntityService manualEntityService;
    @MockitoBean
    private ModeResponseMapper modeResponseMapper;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    private ModeResponse modeResponse(boolean quickAccess) {
        return ModeResponse.builder()
                .entityId("input_boolean.manual_toni_allein")
                .displayName("Toni allein")
                .icon("pets")
                .state("off")
                .quickAccess(quickAccess)
                .build();
    }

    @Test
    void listeDerModiTraegtDieVertraglichenFeldnamen() throws Exception {
        when(houseModeQueryService.listModes()).thenReturn(List.of(modeResponse(true)));

        mockMvc.perform(get("/v1/modes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityId").value("input_boolean.manual_toni_allein"))
                .andExpect(jsonPath("$[0].displayName").value("Toni allein"))
                .andExpect(jsonPath("$[0].icon").value("pets"))
                .andExpect(jsonPath("$[0].state").value("off"))
                .andExpect(jsonPath("$[0].quickAccess").value(true));
    }

    @Test
    void umschaltenLiefertEbenfallsDenVertraglichenFeldnamen() throws Exception {
        EntityState entityState = EntityState.builder()
                .entityId("input_boolean.manual_toni_allein")
                .build();
        when(manualEntityService.toggle("input_boolean.manual_toni_allein")).thenReturn(entityState);
        when(modeResponseMapper.toResponse(entityState)).thenReturn(modeResponse(false));

        mockMvc.perform(post("/v1/modes/input_boolean.manual_toni_allein/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("input_boolean.manual_toni_allein"))
                .andExpect(jsonPath("$.displayName").value("Toni allein"))
                .andExpect(jsonPath("$.icon").value("pets"))
                .andExpect(jsonPath("$.state").value("off"))
                .andExpect(jsonPath("$.quickAccess").value(false));
    }
}
