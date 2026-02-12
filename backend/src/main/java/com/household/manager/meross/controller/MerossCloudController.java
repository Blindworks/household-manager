package com.household.manager.meross.controller;

import com.household.manager.meross.dto.MerossCloudLoginRequest;
import com.household.manager.meross.dto.MerossCloudLoginResponse;
import com.household.manager.meross.dto.MerossCloudDevicesResponse;
import com.household.manager.meross.service.MerossCloudAuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/meross/cloud")
@RequiredArgsConstructor
@Slf4j
public class MerossCloudController {

    private final MerossCloudAuthService merossCloudAuthService;

    @PostMapping("/login")
    public ResponseEntity<MerossCloudLoginResponse> login(@Valid @RequestBody MerossCloudLoginRequest request) {
        log.info("Meross endpoint called: POST /api/meross/cloud/login (email={}, region={})",
                request.email(), request.region());
        return ResponseEntity.ok(merossCloudAuthService.login(request));
    }

    @PostMapping("/login/config")
    public ResponseEntity<MerossCloudLoginResponse> loginWithConfig() {
        log.info("Meross endpoint called: POST /api/meross/cloud/login/config");
        return ResponseEntity.ok(merossCloudAuthService.loginWithConfiguredCredentials());
    }

    @GetMapping("/devices")
    public ResponseEntity<MerossCloudDevicesResponse> listDevices() {
        log.info("Meross endpoint called: GET /api/meross/cloud/devices");
        return ResponseEntity.ok(merossCloudAuthService.listDevicesWithConfiguredCredentials());
    }
}
