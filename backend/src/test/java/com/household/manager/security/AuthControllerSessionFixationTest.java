package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.security.dto.LoginRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Verifiziert, dass ein erfolgreicher Login die Session-ID rotiert
 * (Schutz vor Session-Fixation) — Controller-Login durchlaeuft keine
 * SessionAuthenticationStrategy, die das sonst uebernehmen wuerde.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerSessionFixationTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private SecurityContextRepository securityContextRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private AppUserService appUserService;

    @Mock
    private AppUserDetailsService appUserDetailsService;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void erfolgreicherLoginRotiertDieSessionId() {
        when(authenticationManager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(
                        "bene", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        // Kein Mockito-Mock fuer TokenBasedRememberMeServices noetig (finale Methoden) —
        // eine echte Instanz mit Dummy-Key reicht, loginSuccess() ruft den
        // UserDetailsService dabei ohnehin nicht auf.
        UserDetailsService unusedUserDetailsService = username -> {
            throw new UsernameNotFoundException("nicht relevant fuer diesen Test");
        };
        TokenBasedRememberMeServices rememberMeServices =
                new TokenBasedRememberMeServices("dummy-key", unusedUserDetailsService);

        AuthController controller = new AuthController(
                authenticationManager, securityContextRepository, rememberMeServices, auditService,
                appUserService, appUserDetailsService);

        MockHttpServletRequest request = new MockHttpServletRequest();
        String sessionIdBeforeLogin = request.getSession().getId();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.login(new LoginRequest("bene", "geheim"), request, response);

        assertThat(request.getSession(false).getId()).isNotEqualTo(sessionIdBeforeLogin);
    }
}
