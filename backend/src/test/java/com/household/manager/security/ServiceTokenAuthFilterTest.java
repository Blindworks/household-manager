package com.household.manager.security;

import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceTokenAuthFilterTest {

    @Mock
    private ServiceTokenService serviceTokenService;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void gueltigerTokenSetztAuthentifizierungMitRolleUndServiceAuthority() throws Exception {
        when(serviceTokenService.authenticate("hm_ok")).thenReturn(Optional.of(
                ServiceToken.builder().name("tablet").role(UserRole.KIOSK).enabled(true).build()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Token", "hm_ok");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ServiceTokenAuthFilter(serviceTokenService).doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("tablet");
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_KIOSK", "SERVICE");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void ungueltigerTokenLaesstDenRequestUnauthentifiziertWeiterlaufen() throws Exception {
        when(serviceTokenService.authenticate("falsch")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Token", "falsch");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ServiceTokenAuthFilter(serviceTokenService).doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void ohneHeaderPassiertNichts() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ServiceTokenAuthFilter(serviceTokenService).doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }
}
