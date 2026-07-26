package com.household.manager.security;

import com.household.manager.controller.SwitchController;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import com.household.manager.repository.AppUserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Eigene Slice-Klasse (nicht Teil von SecurityRulesTest): Der csrf()-Test-Helper
 * der Nachbartests wrappt das CsrfTokenRepository klassenweit und unterdrueckt
 * danach das Cookie-Schreiben — hier muss der echte Schreibpfad laufen.
 */
@WebMvcTest(controllers = SwitchController.class)
@Import({SecurityConfig.class, ServiceTokenAuthFilter.class, DisabledUserSessionFilter.class})
class CsrfCookiePathTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SwitchQueryService switchQueryService;
    @MockitoBean
    private SwitchCommandService switchCommandService;
    @MockitoBean
    private ServiceTokenService serviceTokenService;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private AppUserDetailsService appUserDetailsService;

    @Test
    void xsrfCookieWirdMitWurzelPfadGesetzt() throws Exception {
        // Default-Pfad waere der Kontextpfad /api — den saehe document.cookie der
        // unter / laufenden Angular-App nicht, und jeder POST scheiterte mit 403
        Cookie cookie = mockMvc.perform(get("/v1/switches"))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getPath()).isEqualTo("/");
    }
}
