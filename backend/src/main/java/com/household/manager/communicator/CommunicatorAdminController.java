package com.household.manager.communicator;

import com.household.manager.ai.AiProperties;
import com.household.manager.communicator.CommunicatorLogDtos.Detail;
import com.household.manager.communicator.CommunicatorLogDtos.Health;
import com.household.manager.communicator.CommunicatorLogDtos.RowPage;
import com.household.manager.communicator.CommunicatorLogDtos.Summary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Protokoll und Zustand des Communicators. Rolle (ADMIN) steht in SecurityConfig ({@code /v1/admin/**}). */
@RestController
@RequestMapping("/v1/admin/communicator")
@RequiredArgsConstructor
public class CommunicatorAdminController {

    private final CommunicatorLogService logService;
    private final CommunicatorProperties properties;
    private final AiProperties aiProperties;
    private final ApiCatalog catalog;
    private final SpeechModelInstaller modelInstaller;

    @GetMapping("/summary")
    public Summary summary(@RequestParam(defaultValue = "7") int days) {
        return logService.summary(days);
    }

    @GetMapping("/requests")
    public RowPage requests(@RequestParam(defaultValue = "7") int days,
                            @RequestParam(defaultValue = "false") boolean problemsOnly,
                            @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "30") int size) {
        return logService.list(days, problemsOnly, q, page, size);
    }

    @GetMapping("/requests/{id}")
    public ResponseEntity<Detail> request(@PathVariable long id) {
        return ResponseEntity.of(logService.detail(id));
    }

    /** Fragt den Whisper-Dienst bei jedem Aufruf frisch; dauert hoechstens wenige Sekunden. */
    @GetMapping("/health")
    public Health health() {
        return new Health(properties.isEnabled(), aiProperties.isConfigured(), properties.getModel(),
                catalog.endpoints().size(), catalog.index().length(), modelInstaller.health());
    }
}
