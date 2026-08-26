package com.household.manager.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PresenceControllerTest {

    @Mock
    private PresenceStatusService statusService;
    @Mock
    private PresenceDeviceService deviceService;
    @Mock
    private PresenceSettingsService settingsService;
    @Mock
    private PresencePollingService pollingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PresenceController(statusService, deviceService, settingsService, pollingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void statusLiefertDieAntwortDesServices() throws Exception {
        when(statusService.getStatus()).thenReturn(
                new PresenceDtos.StatusResponse("on", List.of()));

        mockMvc.perform(get("/v1/presence/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.householdState").value("on"));
    }

    /**
     * Der Endpunkt muss die Abfrage ANSTOSSEN und danach den FRISCHEN Status zurueckgeben -
     * nicht bloss einen bereits vorher zwischengespeicherten. Die InOrder-Pruefung belegt die
     * Reihenfolge (erst refreshNow(), dann getStatus()), die ein reines "beide wurden
     * aufgerufen" nicht sehen wuerde.
     */
    @Test
    void refreshLoestDenManuellenAbrufAusUndLiefertDenFrischenStatus() throws Exception {
        when(statusService.getStatus()).thenReturn(new PresenceDtos.StatusResponse("on", List.of()));

        mockMvc.perform(post("/v1/presence/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.householdState").value("on"));

        org.mockito.InOrder inOrder = inOrder(pollingService, statusService);
        inOrder.verify(pollingService).refreshNow();
        inOrder.verify(statusService).getStatus();
    }

    @Test
    void settingsLesen() throws Exception {
        when(settingsService.getAwayGraceMinutes()).thenReturn(10L);

        mockMvc.perform(get("/v1/presence/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awayGraceMinutes").value(10));
    }

    @Test
    void settingsSchreibenValidiertUndSpeichert() throws Exception {
        // Stub liefert bewusst einen ANDEREN Wert (20) als der Request-Body (15):
        // die Antwort muss den ECHT ZURUECKGELESENEN Wert zeigen, nicht bloss den
        // Request-Body widerspiegeln. Mit gleichen Werten koennte der Controller
        // den Body durchreichen, statt den Round-Trip ueber den Service zu machen,
        // und der Test wuerde das nicht bemerken.
        when(settingsService.getAwayGraceMinutes()).thenReturn(20L);

        mockMvc.perform(put("/v1/presence/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"awayGraceMinutes\": 15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awayGraceMinutes").value(20));
        verify(settingsService).saveAwayGraceMinutes(eq(15L));
    }

    @Test
    void unplausibleKarenzzeitWirdMit400Abgelehnt() throws Exception {
        mockMvc.perform(put("/v1/presence/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"awayGraceMinutes\": 0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/presence/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"awayGraceMinutes\": 100000}"))
                .andExpect(status().isBadRequest());
        verify(settingsService, never()).saveAwayGraceMinutes(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * Ein fehlender Wert ist kein "ausserhalb des Bereichs" - die alte Fehlermeldung
     * ("muss zwischen 1 und ... liegen") beschwerte sich ueber einen Bereich bei einem
     * Wert, der gar nicht da ist. Eigene Meldung fuer den Fall.
     */
    @Test
    void fehlendeKarenzzeitWirdMitEigenerMeldungAbgelehnt() throws Exception {
        mockMvc.perform(put("/v1/presence/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Die Karenzzeit fehlt."));
        verify(settingsService, never()).saveAwayGraceMinutes(org.mockito.ArgumentMatchers.anyLong());
    }
}
