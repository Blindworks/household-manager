package com.household.manager.tapo;

import com.household.manager.tapo.dto.TapoDiscoveryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/tapo")
@RequiredArgsConstructor
public class TapoController {

    private final TapoDiscoveryService tapoDiscoveryService;

    @GetMapping("/discover")
    public ResponseEntity<List<TapoDiscoveryDto>> discover() {
        return ResponseEntity.ok(tapoDiscoveryService.discoverTapoDevices());
    }
}
