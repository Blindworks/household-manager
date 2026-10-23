package com.household.manager.systemlog;

import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.ServiceTokenService;
import com.household.manager.systemlog.SystemLogDtos.SystemLogEntryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SystemLogController.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemLogQueryService queryService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private ServiceTokenService serviceTokenService;

    @Test
    void entries_returnsPage() throws Exception {
        SystemLogEntryDto dto = new SystemLogEntryDto(5L, LocalDateTime.of(2026, 10, 22, 12, 0),
                SystemLogKind.FLOW_RUN, SystemLogStatus.ERROR, "7", "Flur", "kaputt", null, 7L, "run-1", 1);
        when(queryService.entries(any(), any(), any(), any(), any(), any(), eq(true), any(), any()))
                .thenReturn(new SystemLogDtos.SystemLogPageDto(List.of(dto), 1, 0, 50));

        mockMvc.perform(get("/v1/admin/system-log/entries")
                        .param("kind", "FLOW_RUN", "JOB").param("status", "ERROR")
                        .param("problemsOnly", "true").param("q", "kaputt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.content[0].kind").value("FLOW_RUN"))
                .andExpect(jsonPath("$.content[0].runId").value("run-1"));

        verify(queryService).entries(any(), any(), eq(List.of(SystemLogKind.FLOW_RUN, SystemLogKind.JOB)),
                eq(List.of(SystemLogStatus.ERROR)), any(), eq("kaputt"), eq(true), any(), any());
    }

    @Test
    void runs_returnsList() throws Exception {
        when(queryService.run("abc")).thenReturn(List.of());

        mockMvc.perform(get("/v1/admin/system-log/runs/abc")).andExpect(status().isOk());
    }
}
