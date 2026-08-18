package com.household.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.audit.AuditService;
import com.household.manager.dto.SmartDeviceResponse;
import com.household.manager.exception.GlobalExceptionHandler;
import com.household.manager.kasa.exception.KasaCommunicationException;
import com.household.manager.service.SmartDeviceService;
import com.household.manager.tapo.TapoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verhalten des manuellen Kasa-Add-Endpunkts {@code POST /devices/kasa}, der die in
 * einem Docker-Bridge-Netzwerk blockierte UDP-Discovery umgeht (Real-Steuerung
 * funktioniert dort bereits per TCP-Unicast, nur die Broadcast-Discovery nicht).
 */
@ExtendWith(MockitoExtension.class)
class SmartDeviceControllerTest {

    @Mock
    private SmartDeviceService smartDeviceService;
    @Mock
    private AuditService auditService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SmartDeviceController(smartDeviceService, auditService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("POST /devices/kasa fuegt ein Geraet per IP hinzu, liefert 201 und schreibt ein Audit")
    void addsKasaDeviceByIpAndReturnsCreated() throws Exception {
        SmartDeviceResponse response = SmartDeviceResponse.builder()
                .id(42L)
                .deviceType("KASA")
                .externalDeviceId("8006ABCDEF")
                .deviceName("Kueche")
                .model("HS100(EU)")
                .ipAddress("192.168.1.116")
                .isOnline(true)
                .isPoweredOn(true)
                .build();
        when(smartDeviceService.addKasaDeviceByIp("192.168.1.116")).thenReturn(response);

        mockMvc.perform(post("/devices/kasa")
                        .contentType("application/json")
                        .content("{\"ip\":\"192.168.1.116\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.deviceType").value("KASA"))
                .andExpect(jsonPath("$.ipAddress").value("192.168.1.116"));

        verify(auditService).record("device.kasa.add-manual", "192.168.1.116");
    }

    @Test
    @DisplayName("POST /devices/kasa lehnt eine leere IP mit 400 ab")
    void rejectsBlankIpWithBadRequest() throws Exception {
        mockMvc.perform(post("/devices/kasa")
                        .contentType("application/json")
                        .content("{\"ip\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /devices/kasa lehnt eine nicht-IPv4-Eingabe mit 400 ab")
    void rejectsNonIpv4ValueWithBadRequest() throws Exception {
        mockMvc.perform(post("/devices/kasa")
                        .contentType("application/json")
                        .content("{\"ip\":\"not-an-ip\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /devices/kasa lehnt eine IP mit fuehrender Null im Oktett mit 400 ab "
            + "(JDK 21 wuerde sie sonst als Hostnamen per DNS aufloesen statt als Literal)")
    void rejectsLeadingZeroOctetWithBadRequest() throws Exception {
        mockMvc.perform(post("/devices/kasa")
                        .contentType("application/json")
                        .content("{\"ip\":\"010.1.1.1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /devices/kasa liefert 502, wenn das Geraet nicht antwortet, statt eines opaken 500")
    void mapsKasaCommunicationExceptionToBadGateway() throws Exception {
        when(smartDeviceService.addKasaDeviceByIp("192.168.1.200"))
                .thenThrow(new KasaCommunicationException(
                        "Failed to communicate with Kasa device at IP 192.168.1.200 after 3 attempts"));

        mockMvc.perform(post("/devices/kasa")
                        .contentType("application/json")
                        .content("{\"ip\":\"192.168.1.200\"}"))
                .andExpect(status().isBadGateway());

        verify(smartDeviceService).addKasaDeviceByIp(any());
    }

    @Test
    @DisplayName("PUT /devices/{id}/address setzt die IP eines Tapo-Geraets, liefert 200 und schreibt ein Audit")
    void setsTapoDeviceAddressAndReturnsOk() throws Exception {
        SmartDeviceResponse response = SmartDeviceResponse.builder()
                .id(7L)
                .deviceType("TAPO")
                .externalDeviceId("DEV1")
                .deviceName("Flur")
                .model("L530")
                .ipAddress("192.168.1.114")
                .isOnline(true)
                .isPoweredOn(true)
                .build();
        when(smartDeviceService.setTapoDeviceAddress(7L, "192.168.1.114")).thenReturn(response);

        mockMvc.perform(put("/devices/7/address")
                        .contentType("application/json")
                        .content("{\"ip\":\"192.168.1.114\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.deviceType").value("TAPO"))
                .andExpect(jsonPath("$.ipAddress").value("192.168.1.114"));

        verify(auditService).record(eq("device.tapo.address.set"), any());
    }

    @Test
    @DisplayName("PUT /devices/{id}/address lehnt eine leere IP mit 400 ab")
    void rejectsBlankIpForAddressUpdateWithBadRequest() throws Exception {
        mockMvc.perform(put("/devices/7/address")
                        .contentType("application/json")
                        .content("{\"ip\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /devices/{id}/address lehnt eine nicht-IPv4-Eingabe mit 400 ab")
    void rejectsNonIpv4ValueForAddressUpdateWithBadRequest() throws Exception {
        mockMvc.perform(put("/devices/7/address")
                        .contentType("application/json")
                        .content("{\"ip\":\"not-an-ip\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /devices/{id}/address liefert 400, wenn das Geraet kein Tapo-Geraet ist")
    void rejectsAddressUpdateForNonTapoDeviceWithBadRequest() throws Exception {
        when(smartDeviceService.setTapoDeviceAddress(7L, "192.168.1.114"))
                .thenThrow(new IllegalArgumentException("Geraet mit ID 7 ist kein Tapo-Geraet (Typ: KASA)."));

        mockMvc.perform(put("/devices/7/address")
                        .contentType("application/json")
                        .content("{\"ip\":\"192.168.1.114\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /devices/{id}/address liefert 502, wenn das Geraet unter der IP nicht antwortet, statt eines opaken 500")
    void mapsTapoExceptionToBadGatewayForAddressUpdate() throws Exception {
        when(smartDeviceService.setTapoDeviceAddress(7L, "192.168.1.200"))
                .thenThrow(new TapoException("Tapo-Geraet unter 192.168.1.200 ist weder ueber KLAP noch ueber AES erreichbar."));

        mockMvc.perform(put("/devices/7/address")
                        .contentType("application/json")
                        .content("{\"ip\":\"192.168.1.200\"}"))
                .andExpect(status().isBadGateway());

        verify(smartDeviceService).setTapoDeviceAddress(eq(7L), eq("192.168.1.200"));
    }
}
