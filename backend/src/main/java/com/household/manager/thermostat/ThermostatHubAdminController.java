package com.household.manager.thermostat;

import com.household.manager.thermostat.dto.HubRequest;
import com.household.manager.thermostat.dto.RenameRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Hub-Pflege (ADMIN, siehe SecurityConfig): Hub-IP setzen und Thermostate umbenennen.
 * {@code /hub} ist ein literales Segment und wird von Spring vor {@code /{id}} bevorzugt.
 */
@RestController
@RequestMapping("/v1/thermostats")
@RequiredArgsConstructor
public class ThermostatHubAdminController {

    private final ThermostatHubService hubService;
    private final ThermostatService thermostatService;

    @PutMapping("/hub")
    public ResponseEntity<List<ThermostatDto>> registerHub(@Valid @RequestBody HubRequest request) {
        return ResponseEntity.ok(hubService.registerHub(request.ip()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ThermostatDto> rename(@PathVariable Long id, @Valid @RequestBody RenameRequest request) {
        return ResponseEntity.ok(thermostatService.rename(id, request.name()));
    }
}
