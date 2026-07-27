package com.household.manager.security;

import com.household.manager.security.dto.HouseholdUserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Schlanke Nutzerliste fuer die Personenauswahl im Kalender. {@code /v1/admin/users} ist
 * ADMIN-only — ohne diesen Endpunkt koennte ein MEMBER keine Person auswaehlen.
 * Ausgeliefert werden bewusst nur Id, Anzeigename und Aktiv-Flag: keine Rolle, kein
 * Benutzername. Die Leseberechtigung ergibt sich aus der generischen GET-Regel in
 * SecurityConfig (KIOSK und darueber).
 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
public class HouseholdUserController {

    private final AppUserService service;

    @GetMapping
    public ResponseEntity<List<HouseholdUserResponse>> list() {
        return ResponseEntity.ok(service.list().stream()
                .map(HouseholdUserResponse::of)
                .toList());
    }
}
