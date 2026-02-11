package com.household.manager.kasa;

import com.household.manager.kasa.dto.KasaDiscoveryDto;
import com.household.manager.kasa.dto.KasaStatusDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/kasa")
@RequiredArgsConstructor
public class KasaController {

    private final KasaService kasaService;
    private final KasaDiscoveryService kasaDiscoveryService;

    @PostMapping("/{ip:.+}/on")
    public ResponseEntity<Void> turnOn(@PathVariable String ip) {
        kasaService.turnOn(ip);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{ip:.+}/off")
    public ResponseEntity<Void> turnOff(@PathVariable String ip) {
        kasaService.turnOff(ip);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{ip:.+}/status")
    public ResponseEntity<KasaStatusDto> getStatus(@PathVariable String ip) {
        return ResponseEntity.ok(kasaService.getStatus(ip));
    }

    @GetMapping("/discover")
    public ResponseEntity<List<KasaDiscoveryDto>> discover() {
        return ResponseEntity.ok(kasaDiscoveryService.discover());
    }
}
