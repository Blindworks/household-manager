package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.security.dto.ChangePasswordRequest;
import com.household.manager.security.dto.CurrentUserResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

/**
 * Sicherheitskritischer Pfad: Nach dem Selbst-Passwortwechsel muss die
 * Session einen FRISCH geladenen Principal tragen (must_change_password
 * geloescht) und das Remember-Me-Cookie mit dem neuen Hash erneuert werden —
 * sonst bliebe die Session im Pflichtwechsel-Zustand haengen bzw. das alte
 * Cookie wuerde still unbrauchbar.
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerPasswordChangeTest {

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
    void passwortwechselErneuertSessionPrincipalUndRememberMeCookie() {
        AppUserPrincipal refreshedPrincipal = new AppUserPrincipal(AppUser.builder()
                .username("admin").displayName("Administrator").passwordHash("$2a$neuerHash")
                .role(UserRole.ADMIN).enabled(true).mustChangePassword(false).build());
        when(appUserDetailsService.loadUserByUsername("admin")).thenReturn(refreshedPrincipal);

        TokenBasedRememberMeServices rememberMeServices =
                new TokenBasedRememberMeServices("dummy-key", appUserDetailsService);
        // wie die echte Bean in SecurityConfig konfiguriert
        rememberMeServices.setAlwaysRemember(true);
        rememberMeServices.setCookieName("HM_REMEMBER");
        AuthController controller = new AuthController(
                authenticationManager, securityContextRepository, rememberMeServices, auditService,
                appUserService, appUserDetailsService);

        // Ausgangslage: Session-Principal traegt noch das Pflichtwechsel-Flag
        Authentication staleAuth = UsernamePasswordAuthenticationToken.authenticated(
                new AppUserPrincipal(AppUser.builder()
                        .username("admin").displayName("Administrator").passwordHash("$2a$alterHash")
                        .role(UserRole.ADMIN).enabled(true).mustChangePassword(true).build()),
                null, refreshedPrincipal.getAuthorities());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        CurrentUserResponse result = controller.changePassword(
                new ChangePasswordRequest("changeit", "neuesPasswort1"), staleAuth, request, response);

        // Passwort wurde VOR dem Neuladen des Principals geaendert
        InOrder order = inOrder(appUserService, appUserDetailsService);
        order.verify(appUserService).changeOwnPassword("admin", "changeit", "neuesPasswort1");
        order.verify(appUserDetailsService).loadUserByUsername("admin");

        // Session traegt den frischen Principal, Antwort meldet das geloeschte Flag
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        assertThat(current.getPrincipal()).isSameAs(refreshedPrincipal);
        assertThat(result.mustChangePassword()).isFalse();
        org.mockito.Mockito.verify(securityContextRepository).saveContext(any(), any(), any());

        // Remember-Me-Cookie wurde mit dem frischen Principal neu ausgestellt
        assertThat(response.getCookie("HM_REMEMBER")).isNotNull();
        assertThat(response.getCookie("HM_REMEMBER").getMaxAge()).isPositive();
    }
}
