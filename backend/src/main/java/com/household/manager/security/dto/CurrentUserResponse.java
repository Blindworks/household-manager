package com.household.manager.security.dto;

import com.household.manager.security.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Der angemeldete Aktor, wie ihn das Frontend braucht. */
public record CurrentUserResponse(String username, String displayName, String role,
                                  boolean mustChangePassword) {

    public static CurrentUserResponse from(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .findFirst()
                .orElse("KIOSK");
        boolean isUser = authentication.getPrincipal() instanceof AppUserPrincipal;
        String displayName = isUser
                ? ((AppUserPrincipal) authentication.getPrincipal()).getDisplayName()
                : authentication.getName();
        boolean mustChangePassword = isUser
                && ((AppUserPrincipal) authentication.getPrincipal()).isMustChangePassword();
        return new CurrentUserResponse(authentication.getName(), displayName, role, mustChangePassword);
    }
}
