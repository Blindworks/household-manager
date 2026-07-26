package com.household.manager.security;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.security.dto.UserAdminDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Service-Token-Verwaltung (nur ADMIN). */
@RestController
@RequestMapping("/v1/admin/service-tokens")
@RequiredArgsConstructor
public class ServiceTokenAdminController {

    private final ServiceTokenService serviceTokenService;
    private final AuditService auditService;

    @GetMapping
    public List<TokenResponse> list() {
        return serviceTokenService.list().stream().map(TokenResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedTokenResponse create(@Valid @RequestBody CreateTokenRequest request) {
        ServiceTokenService.CreatedToken created = serviceTokenService.create(request.name(), request.role());
        auditService.record("token.create", created.token().getName()
                + " (" + created.token().getRole() + ")");
        return new CreatedTokenResponse(TokenResponse.from(created.token()), created.plaintext());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id) {
        ServiceToken token = serviceTokenService.revoke(id);
        auditService.record("token.revoke", token.getName());
    }
}
