package com.household.manager.dirigera;

import com.household.manager.dirigera.DirigeraDtos.HostRequest;
import com.household.manager.dirigera.DirigeraDtos.HubView;
import com.household.manager.dirigera.DirigeraDtos.SensorView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dirigera-Admin-API. Rollen (ADMIN, auch Lesen) stehen in SecurityConfig, nicht hier. */
@RestController
@RequestMapping("/v1/dirigera")
@RequiredArgsConstructor
public class DirigeraController {

    private final DirigeraAdminService adminService;
    private final DirigeraPairingService pairingService;

    @GetMapping("/hub")
    public HubView hub() {
        return adminService.hub();
    }

    @PutMapping("/hub")
    public HubView updateHub(@RequestBody HostRequest request) {
        return adminService.updateHost(request.host());
    }

    @PostMapping("/pairing")
    public DirigeraPairingStatus startPairing() {
        return pairingService.start();
    }

    @GetMapping("/pairing")
    public DirigeraPairingStatus pairingStatus() {
        return pairingService.status();
    }

    @DeleteMapping("/pairing")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unpair() {
        pairingService.unpair();
    }

    @GetMapping("/sensors")
    public List<SensorView> sensors() {
        return adminService.sensors();
    }

    @DeleteMapping("/sensors/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSensor(@PathVariable Long id) {
        adminService.deleteSensor(id);
    }
}
