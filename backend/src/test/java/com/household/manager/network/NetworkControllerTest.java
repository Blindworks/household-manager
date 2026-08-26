package com.household.manager.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.household.manager.dto.TimeValue;
import com.household.manager.exception.GlobalExceptionHandler;
import com.household.manager.exception.TooManyRequestsException;
import com.household.manager.model.entity.NetworkSpeedtestResult;
import com.household.manager.service.SeriesRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
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

@ExtendWith(MockitoExtension.class)
class NetworkControllerTest {

    @Mock
    private NetworkStatusService statusService;
    @Mock
    private NetworkHistoryService historyService;
    @Mock
    private NetworkSpeedtestService speedtestService;
    @Mock
    private NetworkDeviceService deviceService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new NetworkController(statusService, historyService, speedtestService, deviceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void status_returnsStatusFromService() throws Exception {
        NetworkDtos.StatusResponse response = new NetworkDtos.StatusResponse(
                true, 25, true, LocalDateTime.of(2026, 8, 24, 10, 0), null, List.of());
        when(statusService.getStatus()).thenReturn(response);

        mockMvc.perform(get("/v1/network/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(true))
                .andExpect(jsonPath("$.latencyMs").value(25))
                .andExpect(jsonPath("$.gatewayReachable").value(true));
    }

    @Test
    void history_usesWeekAsDefaultRange() throws Exception {
        when(historyService.getHistory(any())).thenReturn(
                new NetworkDtos.HistoryResponse(List.of(), List.of()));

        mockMvc.perform(get("/v1/network/history")).andExpect(status().isOk());

        verify(historyService).getHistory(SeriesRange.WEEK);
    }

    @Test
    void history_passesThroughSelectedRange() throws Exception {
        when(historyService.getHistory(any())).thenReturn(
                new NetworkDtos.HistoryResponse(List.of(), List.of()));

        mockMvc.perform(get("/v1/network/history?range=MONTH")).andExpect(status().isOk());

        verify(historyService).getHistory(SeriesRange.MONTH);
    }

    @Test
    void history_returnsLatencyAndSpeedtestPoints() throws Exception {
        LocalDateTime time = LocalDateTime.of(2026, 8, 24, 9, 30);
        when(historyService.getHistory(any())).thenReturn(new NetworkDtos.HistoryResponse(
                List.of(TimeValue.builder().time(time).value(new BigDecimal("30")).build()),
                List.of(new NetworkDtos.SpeedtestPoint(time, new BigDecimal("100.00"), new BigDecimal("20.00")))));

        mockMvc.perform(get("/v1/network/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latency[0].value").value(30))
                .andExpect(jsonPath("$.speedtests[0].downloadMbps").value(100.00));
    }

    @Test
    void history_invalidRange_returns400() throws Exception {
        mockMvc.perform(get("/v1/network/history?range=NOT_A_RANGE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void speedtest_delegatesToRunManualAndReturnsSummary() throws Exception {
        NetworkSpeedtestResult result = NetworkSpeedtestResult.builder()
                .testedAt(LocalDateTime.of(2026, 8, 24, 10, 5))
                .downloadMbps(new BigDecimal("150.00"))
                .uploadMbps(new BigDecimal("40.00"))
                .success(true)
                .build();
        when(speedtestService.runManual()).thenReturn(result);

        mockMvc.perform(post("/v1/network/speedtest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadMbps").value(150.00))
                .andExpect(jsonPath("$.uploadMbps").value(40.00))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void speedtest_cooldownViolation_returns429() throws Exception {
        when(speedtestService.runManual()).thenThrow(new TooManyRequestsException("zu frueh"));

        mockMvc.perform(post("/v1/network/speedtest"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void speedtest_offline_returns400() throws Exception {
        when(speedtestService.runManual()).thenThrow(new IllegalStateException("Kein Internet — Speedtest nicht möglich."));

        mockMvc.perform(post("/v1/network/speedtest"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void devices_list_delegatesToDeviceService() throws Exception {
        when(deviceService.list()).thenReturn(List.of(
                new NetworkDtos.DeviceAdminResponse(1L, "Router", "192.168.1.1", null, 0, true)));

        mockMvc.perform(get("/v1/network/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Router"));
    }

    @Test
    void devices_create_returns201() throws Exception {
        NetworkDtos.DeviceRequest request = new NetworkDtos.DeviceRequest("Router", "192.168.1.1", null, 0, true);
        NetworkDtos.DeviceAdminResponse response =
                new NetworkDtos.DeviceAdminResponse(1L, "Router", "192.168.1.1", null, 0, true);
        when(deviceService.create(any())).thenReturn(response);

        mockMvc.perform(post("/v1/network/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void devices_update_delegatesWithId() throws Exception {
        NetworkDtos.DeviceRequest request = new NetworkDtos.DeviceRequest("Router", "192.168.1.1", 443, 1, true);
        NetworkDtos.DeviceAdminResponse response =
                new NetworkDtos.DeviceAdminResponse(1L, "Router", "192.168.1.1", 443, 1, true);
        when(deviceService.update(eq(1L), any())).thenReturn(response);

        mockMvc.perform(put("/v1/network/devices/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tcpPort").value(443));
    }

    @Test
    void devices_delete_returns204() throws Exception {
        mockMvc.perform(delete("/v1/network/devices/1"))
                .andExpect(status().isNoContent());

        verify(deviceService).delete(1L);
    }
}
