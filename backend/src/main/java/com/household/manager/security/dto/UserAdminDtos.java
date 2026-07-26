package com.household.manager.security.dto;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** DTOs der Admin-Endpunkte fuer Nutzer- und Token-Verwaltung. */
public final class UserAdminDtos {

    private UserAdminDtos() {
    }

    public record UserResponse(Long id, String username, String displayName, UserRole role,
                               boolean enabled, LocalDateTime createdAt) {
        public static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                    user.getRole(), user.isEnabled(), user.getCreatedAt());
        }
    }

    public record CreateUserRequest(@NotBlank @Size(max = 100) String username,
                                    @NotBlank @Size(max = 200) String displayName,
                                    @NotBlank @Size(min = 8) String password,
                                    @NotNull UserRole role) {
    }

    public record UpdateUserRequest(@NotBlank @Size(max = 200) String displayName,
                                    @NotNull UserRole role,
                                    boolean enabled) {
    }

    public record PasswordRequest(@NotBlank @Size(min = 8) String password) {
    }

    public record TokenResponse(Long id, String name, UserRole role, boolean enabled,
                                LocalDateTime createdAt, LocalDateTime lastUsedAt) {
        public static TokenResponse from(ServiceToken token) {
            return new TokenResponse(token.getId(), token.getName(), token.getRole(),
                    token.isEnabled(), token.getCreatedAt(), token.getLastUsedAt());
        }
    }

    public record CreateTokenRequest(@NotBlank @Size(max = 100) String name, @NotNull UserRole role) {
    }

    /** Nur direkt nach der Erstellung enthaelt token den Klartext. */
    public record CreatedTokenResponse(TokenResponse info, String token) {
    }
}
