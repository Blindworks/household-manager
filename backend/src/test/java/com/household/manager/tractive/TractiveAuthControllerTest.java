package com.household.manager.tractive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.tractive.dto.TractiveAuthStatusDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class TractiveAuthControllerTest {

    @Mock
    private TractiveAuthService authService;

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new TractiveAuthController(authService))
                .setControllerAdvice(new TractiveAuthController.TractiveAuthExceptionHandler())
                .build();
    }

    @Test
    void loginReturnsStatus() throws Exception {
        LocalDateTime expiry = LocalDateTime.parse("2026-09-01T00:00:00");
        when(authService.login("halter@example.com", "geheim"))
                .thenReturn(new TractiveAuthStatusDto(true, "halter@example.com", expiry));

        mockMvc().perform(post("/v1/tractive/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                Map.of("email", "halter@example.com", "password", "geheim"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.email").value("halter@example.com"));
    }

    @Test
    void failedLoginReturns401() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenThrow(new TractiveException("falsche Zugangsdaten"));

        mockMvc().perform(post("/v1/tractive/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                Map.of("email", "a@b.de", "password", "falsch"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutDelegatesToService() throws Exception {
        mockMvc().perform(post("/v1/tractive/logout"))
                .andExpect(status().isNoContent());
        verify(authService).logout();
    }
}
