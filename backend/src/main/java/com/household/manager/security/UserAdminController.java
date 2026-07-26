package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.AppUser;
import com.household.manager.security.dto.UserAdminDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Nutzerverwaltung (nur ADMIN — via URL-Regel /v1/admin/** in SecurityConfig). */
@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final AppUserService appUserService;
    private final AuditService auditService;

    @GetMapping
    public List<UserResponse> list() {
        return appUserService.list().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        AppUser user = appUserService.create(request.username(), request.displayName(),
                request.password(), request.role());
        auditService.record("user.create", user.getUsername() + " (" + user.getRole() + ")");
        return UserResponse.from(user);
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        AppUser user = appUserService.update(id, request.displayName(), request.role(), request.enabled());
        auditService.record("user.update", user.getUsername() + " (" + user.getRole()
                + (user.isEnabled() ? ", aktiv" : ", deaktiviert") + ")");
        return UserResponse.from(user);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@PathVariable Long id, @Valid @RequestBody PasswordRequest request) {
        appUserService.setPassword(id, request.password());
        auditService.record("user.set-password", "Nutzer-Id " + id);
    }
}
