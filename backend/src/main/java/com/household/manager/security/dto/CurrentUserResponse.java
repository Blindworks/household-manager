package com.household.manager.security.dto;

import com.household.manager.security.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Der angemeldete Aktor, wie ihn das Frontend braucht. {@code id} ist null
 * bei Service-Tokens; {@code wallTablet} ist dort immer false.
 */
public record CurrentUserResponse(Long id, String username, String displayName, String role,
                                  boolean mustChangePassword, boolean wallTablet) {

    public static CurrentUserResponse from(Authentication authentication, boolean wallTablet) {
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse("KIOSK");
        boolean isUser = authentication.getPrincipal() instanceof AppUserPrincipal;
        AppUserPrincipal principal = isUser ? (AppUserPrincipal) authentication.getPrincipal() : null;
        String displayName = isUser ? principal.getDisplayName() : authentication.getName();
        boolean mustChangePassword = isUser && principal.isMustChangePassword();
        Long id = isUser ? principal.getId() : null;
        return new CurrentUserResponse(id, authentication.getName(), displayName, role,
                mustChangePassword, isUser && wallTablet);
    }
}
