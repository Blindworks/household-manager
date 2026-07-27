package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Belegt den eigentlichen Zweck dieses Endpunkts neben /v1/admin/users: die Antwort
 * darf weder Benutzername noch Rolle enthalten, weil auch das KIOSK-Wandtablet
 * mitliest.
 */
@ExtendWith(MockitoExtension.class)
class HouseholdUserControllerTest {

    @Mock
    private AppUserService appUserService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HouseholdUserController(appUserService)).build();
    }

    @Test
    void listeEnthaeltNurIdAnzeigenameUndAktivFlag() throws Exception {
        when(appUserService.list()).thenReturn(List.of(
                AppUser.builder().id(1L).username("bene").displayName("Benedikt")
                        .passwordHash("x").role(UserRole.ADMIN).enabled(true).build(),
                AppUser.builder().id(2L).username("tablet-account").displayName("Kiosk-Konto")
                        .passwordHash("x").role(UserRole.KIOSK).enabled(false).build()));

        mockMvc.perform(get("/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Benedikt"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].username").doesNotExist())
                .andExpect(jsonPath("$[0].role").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].displayName").value("Kiosk-Konto"))
                .andExpect(jsonPath("$[1].enabled").value(false))
                .andExpect(jsonPath("$[1].username").doesNotExist())
                .andExpect(jsonPath("$[1].role").doesNotExist());
    }
}
