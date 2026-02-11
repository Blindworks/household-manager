package com.household.manager.tapo;

import com.household.manager.tapo.dto.TapoDiscoveryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/tapo")
@RequiredArgsConstructor
@Slf4j
public class TapoController {

    private final TapoDiscoveryService tapoDiscoveryService;

    @GetMapping("/discover")
    public ResponseEntity<List<TapoDiscoveryDto>> discover() {
        log.info("Received Tapo discovery request on /api/tapo/discover");
        List<TapoDiscoveryDto> devices = tapoDiscoveryService.discoverTapoDevices();
        log.info("Tapo discovery completed with {} device(s)", devices.size());
        return ResponseEntity.ok(devices);
    }
}
