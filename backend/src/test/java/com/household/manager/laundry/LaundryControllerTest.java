package com.household.manager.laundry;

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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LaundryController.class)
@AutoConfigureMockMvc(addFilters = false)
class LaundryControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private LaundryDryingService service;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    @Test
    void stateLiefertAktuellenZustand() throws Exception {
        when(service.currentState()).thenReturn(new LaundryStateView("asking",
                LaundryStateView.PROMPT_HUNG, null, null, false, 12.5));

        mockMvc.perform(get("/v1/laundry/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("asking"))
                .andExpect(jsonPath("$.prompt").value("HUNG"))
                .andExpect(jsonPath("$.requiredLoad").value(12.5));
    }

    @Test
    void antwortOhneFrageGibt409() throws Exception {
        doThrow(new LaundryConflictException("Keine offene Frage")).when(service).answerHung(anyBoolean());

        mockMvc.perform(post("/v1/laundry/answers/hung")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"yes\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Keine offene Frage"));
    }

    @Test
    void handmeldungStartetTrocknen() throws Exception {
        when(service.currentState()).thenReturn(new LaundryStateView("drying",
                LaundryStateView.PROMPT_NONE, null, null, false, 17.5));

        mockMvc.perform(post("/v1/laundry/hung"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("drying"));
        verify(service).reportHung();
    }

    @Test
    void handmeldungWaehrendTrocknenGibt409() throws Exception {
        doThrow(new LaundryConflictException("Es trocknet bereits Wäsche im Keller.")).when(service).reportHung();

        mockMvc.perform(post("/v1/laundry/hung"))
                .andExpect(status().isConflict());
    }

    @Test
    void sessionsLiefertGelerntenWert() throws Exception {
        when(service.requiredLoad()).thenReturn(14.0);
        when(service.recentSessions()).thenReturn(List.of());

        mockMvc.perform(get("/v1/laundry/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredLoad").value(14.0))
                .andExpect(jsonPath("$.sessions").isArray());
    }
}
