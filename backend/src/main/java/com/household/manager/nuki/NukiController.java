package com.household.manager.nuki;

import com.household.manager.nuki.dto.NukiActionRequest;
import com.household.manager.nuki.dto.NukiLockResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** REST-Endpoints für die Nuki-Schlösser (Dashboard-Kachel). */
@RestController
@RequestMapping("/v1/nuki")
@RequiredArgsConstructor
public class NukiController {

    private final NukiLockService lockService;

    @GetMapping("/locks")
    public List<NukiLockResponse> getLocks() {
        return lockService.listLocks();
    }

    @PostMapping("/locks/{smartlockId}/actions")
    public ResponseEntity<Void> executeAction(@PathVariable long smartlockId,
                                              @Valid @RequestBody NukiActionRequest request) {
        lockService.executeAction(smartlockId, request.action());
        return ResponseEntity.noContent().build();
    }
}
