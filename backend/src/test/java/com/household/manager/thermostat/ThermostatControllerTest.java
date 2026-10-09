package com.household.manager.thermostat;

import com.household.manager.audit.AuditService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.Thermostat;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.repository.ThermostatRepository;
import com.household.manager.security.ServiceTokenService;
import com.household.manager.service.SeriesRange;
import com.household.manager.tapo.TapoException;
import com.household.manager.tapo.hub.HubProbeResult;
import com.household.manager.tapo.hub.TapoHubClient;
import com.household.manager.tapo.hub.ThermostatMode;
import com.household.manager.tapo.hub.ThermostatReading;
import com.household.manager.thermostat.dto.HistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Echter {@link ThermostatService} und {@link ThermostatHubService} hinter dem Controller, damit
 * die 400er der Validierung wirklich durch die HTTP-Schicht laufen und nicht nur ein gestubbter
 * Service sie wirft.
 */
@WebMvcTest(controllers = {ThermostatController.class, ThermostatHubAdminController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({ThermostatService.class, ThermostatHubService.class, ThermostatStateCache.class,
        ThermostatControllerTest.FixedClock.class})
class ThermostatControllerTest {

    private static final String HUB_IP = "192.168.1.50";

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-09T16:30:00Z"), ZoneId.of("Europe/Berlin"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ThermostatRepository repository;
    @MockitoBean
    private TapoHubClient hubClient;
    @MockitoBean
    private AuditService auditService;
    @MockitoBean
    private ThermostatHistoryService historyService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    private Thermostat living;

    @BeforeEach
    void setUp() {
        living = Thermostat.builder().id(1L).childDeviceId("KE-1").hubDeviceId("HUB-1").hubIp(HUB_IP)
                .name("Wohnzimmer").active(true).build();
        when(repository.findByActiveTrue()).thenReturn(List.of(living));
        when(repository.findById(1L)).thenReturn(Optional.of(living));
        when(repository.findById(99L)).thenReturn(Optional.empty());
        when(repository.findByHubIp(HUB_IP)).thenReturn(List.of(living));
        when(hubClient.readThermostats(HUB_IP)).thenReturn(List.of(
                new ThermostatReading("KE-1", "Wohnzimmer", true, 20.5, 21.5, ThermostatMode.MANUAL, true, false)));
    }

    @Test
    @DisplayName("GET /v1/thermostats liefert die Liste nach Vertrag")
    void listsThermostats() throws Exception {
        mockMvc.perform(get("/v1/thermostats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Wohnzimmer"))
                .andExpect(jsonPath("$[0].mode").value("MANUAL"));
    }

    @Test
    @DisplayName("PUT target: 200 mit aktualisiertem Thermostat")
    void setsTarget() throws Exception {
        mockMvc.perform(put("/v1/thermostats/1/target").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTemp\": 21.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetTemp").value(21.5))
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.heating").value(true))
                .andExpect(jsonPath("$.lastUpdated").value("2026-10-09T18:30:00"));
        verify(hubClient).setTargetTemp(HUB_IP, "KE-1", 21.5);
    }

    @Test
    @DisplayName("PUT target: 21.3, 31 und fehlender Wert sind 400 ohne Geraetezugriff")
    void rejectsInvalidTargets() throws Exception {
        for (String body : List.of("{\"targetTemp\": 21.3}", "{\"targetTemp\": 31}", "{}")) {
            mockMvc.perform(put("/v1/thermostats/1/target").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(put("/v1/thermostats/1/target").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        verify(hubClient, never()).setTargetTemp(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("Unbekannte Id ist 404")
    void unknownIdIs404() throws Exception {
        mockMvc.perform(put("/v1/thermostats/99/target").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTemp\": 21.5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Hub nicht erreichbar ist 502")
    void hubFailureIs502() throws Exception {
        doThrow(new TapoException("Hub weg")).when(hubClient).setTargetTemp(HUB_IP, "KE-1", 21.5);

        mockMvc.perform(put("/v1/thermostats/1/target").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTemp\": 21.5}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("PUT mode setzt den Modus; ungueltiger Modus ist 400")
    void setsMode() throws Exception {
        mockMvc.perform(put("/v1/thermostats/1/mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"FROST_PROTECTION\"}"))
                .andExpect(status().isOk());
        verify(hubClient).setFrostProtection(HUB_IP, "KE-1", true);

        mockMvc.perform(put("/v1/thermostats/1/mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"TURBO\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST bulk ohne ids trifft alle aktiven, mit ungueltigem Wert 400")
    void bulk() throws Exception {
        mockMvc.perform(post("/v1/thermostats/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetTemp\": 17.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
        verify(hubClient).setTargetTemp(HUB_IP, "KE-1", 17.0);

        mockMvc.perform(post("/v1/thermostats/bulk").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [1], \"targetTemp\": 17.2}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET history liefert die Punkte und kennt DAY/WEEK, ungueltiger Zeitraum ist 400")
    void history() throws Exception {
        when(historyService.history(eq(1L), eq(SeriesRange.WEEK))).thenReturn(new HistoryResponse(List.of(
                new HistoryResponse.Point(LocalDateTime.of(2026, 10, 9, 18, 0), 20.4, 21.0, false))));

        mockMvc.perform(get("/v1/thermostats/1/history").param("range", "WEEK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].time").value("2026-10-09T18:00:00"))
                .andExpect(jsonPath("$.points[0].currentTemp").value(20.4))
                .andExpect(jsonPath("$.points[0].heating").value(false));

        mockMvc.perform(get("/v1/thermostats/1/history").param("range", "YEAR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT hub: liest den Hub, legt Thermostate an und liefert sie")
    void registersHub() throws Exception {
        when(hubClient.probeHub(HUB_IP)).thenReturn(new HubProbeResult("HUB-1", List.of(
                new ThermostatReading("KE-1", "Wohnzimmer", true, 20.5, 21.5, ThermostatMode.MANUAL, false, false),
                new ThermostatReading("KE-9", "Neu", true, 19.0, 20.0, ThermostatMode.MANUAL, false, false))));
        when(repository.findByChildDeviceId("KE-1")).thenReturn(Optional.of(living));
        when(repository.findByChildDeviceId("KE-9")).thenReturn(Optional.empty());
        when(repository.save(any(Thermostat.class))).thenAnswer(invocation -> {
            Thermostat saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(9L);
            }
            return saved;
        });

        mockMvc.perform(put("/v1/thermostats/hub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\": \"" + HUB_IP + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].id").value(9))
                .andExpect(jsonPath("$[1].name").value("Neu"));
    }

    @Test
    @DisplayName("PUT hub: Identitaetsabweichung (IP gehoert zu anderem Hub) ist 400 ohne Schreiben")
    void hubIdentityMismatchIs400() throws Exception {
        when(hubClient.probeHub(HUB_IP)).thenReturn(new HubProbeResult("ANDERER-HUB", List.of()));

        mockMvc.perform(put("/v1/thermostats/hub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\": \"" + HUB_IP + "\"}"))
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("PUT hub: nicht erreichbarer Hub ist 502, leere IP 400")
    void hubUnreachableIs502() throws Exception {
        when(hubClient.probeHub(HUB_IP)).thenThrow(new TapoException("nicht erreichbar"));

        mockMvc.perform(put("/v1/thermostats/hub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\": \"" + HUB_IP + "\"}"))
                .andExpect(status().isBadGateway());
        mockMvc.perform(put("/v1/thermostats/hub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ip\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT {id} benennt um, leerer Name ist 400, unbekannte Id 404")
    void renames() throws Exception {
        when(repository.save(any(Thermostat.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(put("/v1/thermostats/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Esszimmer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Esszimmer"));
        mockMvc.perform(put("/v1/thermostats/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"  \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/thermostats/99").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"X\"}"))
                .andExpect(status().isNotFound());
    }
}
