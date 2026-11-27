package com.household.manager.tesla;

import com.household.manager.tesla.dto.TeslaCommandRequest;
import com.household.manager.tesla.dto.TeslaStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard-Kachel: Lesen KIOSK über die generische GET-Regel, Befehle MEMBER
 * ({@code anyRequest}; das Wandtablet-Konto ist MEMBER).
 */
@RestController
@RequestMapping("/v1/tesla/vehicle")
@RequiredArgsConstructor
public class TeslaController {

    private final TeslaVehicleService vehicleService;

    /** Letzter Stand aus dem Speicher, fragt Tessie nicht. */
    @GetMapping
    public TeslaStatusResponse status() {
        return vehicleService.status();
    }

    @PostMapping("/commands")
    public TeslaStatusResponse command(@Valid @RequestBody TeslaCommandRequest request) {
        return vehicleService.execute(request.command());
    }
}
