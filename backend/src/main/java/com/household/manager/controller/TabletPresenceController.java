package com.household.manager.controller;

import com.household.manager.dto.TabletPresenceRequest;
import com.household.manager.tablet.TabletPresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Präsenz-Meldungen der Wandtablet-App. Tablets registrieren sich implizit
 * mit der ersten Meldung (kein Verwaltungs-UI).
 */
@RestController
@RequestMapping("/v1/tablet-presence")
@RequiredArgsConstructor
public class TabletPresenceController {

    private final TabletPresenceService tabletPresenceService;

    @PostMapping("/{tabletId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportPresence(@PathVariable String tabletId, @RequestBody TabletPresenceRequest request) {
        tabletPresenceService.reportPresence(tabletId, request.present());
    }
}
