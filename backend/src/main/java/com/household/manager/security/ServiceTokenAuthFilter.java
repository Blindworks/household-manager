package com.household.manager.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authentifiziert Maschinen-Clients ueber den Header X-API-Token. Ein
 * ungueltiger Token fuehrt nicht zum Abbruch — der Request laeuft
 * unauthentifiziert weiter und scheitert dann an der Autorisierung (401).
 */
@Component
@RequiredArgsConstructor
public class ServiceTokenAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-API-Token";

    private final ServiceTokenService serviceTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String rawToken = request.getHeader(TOKEN_HEADER);
        if (StringUtils.hasText(rawToken)) {
            serviceTokenService.authenticate(rawToken).ifPresent(token -> {
                List<GrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + token.getRole().name()),
                        new SimpleGrantedAuthority(SecurityConfig.SERVICE_AUTHORITY));
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        token.getName(), null, authorities));
                SecurityContextHolder.setContext(context);
            });
        }
        filterChain.doFilter(request, response);
    }
}
