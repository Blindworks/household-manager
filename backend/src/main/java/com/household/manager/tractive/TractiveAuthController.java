package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveAuthStatusDto;
import com.household.manager.tractive.dto.TractiveLoginRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Anmeldung an der Tractive-Cloud (In-App-Login). */
@RestController
@RequestMapping("/v1/tractive")
@RequiredArgsConstructor
public class TractiveAuthController {

    private final TractiveAuthService authService;

    @PostMapping("/login")
    public TractiveAuthStatusDto login(@Valid @RequestBody TractiveLoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @GetMapping("/status")
    public TractiveAuthStatusDto status() {
        return authService.status();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout();
        return ResponseEntity.noContent().build();
    }
}
