package com.household.manager.charging;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * „Benachrichtigen, wenn frei“ am Wandtablet. Lesen KIOSK ueber die generische GET-Regel,
 * PUT/DELETE KIOSK (Muster Favoriten-Stern), kein Audit.
 */
@RestController
@RequestMapping("/v1/charging")
@RequiredArgsConstructor
public class ChargingWatchController {

    private final ChargingWatchService watchService;

    @GetMapping("/watches")
    public List<ChargingWatchService.ChargingWatch> watches() {
        return watchService.list();
    }

    @GetMapping("/watch-candidates")
    public List<ChargingWatchService.WatchCandidate> candidates() {
        return watchService.candidates();
    }

    @PutMapping("/watches/{stationId}/{userId}")
    public ResponseEntity<List<ChargingWatchService.ChargingWatch>> add(@PathVariable String stationId,
                                                                        @PathVariable Long userId) {
        watchService.add(stationId, userId);
        return ResponseEntity.ok(watchService.list());
    }

    @DeleteMapping("/watches/{stationId}/{userId}")
    public ResponseEntity<List<ChargingWatchService.ChargingWatch>> remove(@PathVariable String stationId,
                                                                           @PathVariable Long userId) {
        watchService.remove(stationId, userId);
        return ResponseEntity.ok(watchService.list());
    }
}
