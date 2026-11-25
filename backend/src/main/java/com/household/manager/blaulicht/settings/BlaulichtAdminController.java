package com.household.manager.blaulicht.settings;

import com.household.manager.audit.AuditService;
import com.household.manager.blaulicht.BlaulichtPollingService;
import com.household.manager.blaulicht.dto.BlaulichtSettingsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-Seite „Blaulicht“: fachliche Einstellungen lesen und speichern. ADMIN über den methodenlosen
 * {@code /v1/admin/**}-Matcher (auch Lesen). Nach dem Speichern wird die Übersicht neu gerechnet, damit
 * Hub-Karte und {@code sensor.blaulicht_overnight} die neuen Uhrzeiten und Orte sofort zeigen.
 */
@RestController
@RequestMapping("/v1/admin/blaulicht")
@RequiredArgsConstructor
public class BlaulichtAdminController {

    private final BlaulichtSettingsService settings;
    private final BlaulichtPollingService polling;
    private final AuditService auditService;

    @GetMapping("/settings")
    public BlaulichtSettingsDto getSettings() {
        return BlaulichtSettingsDto.from(settings.current());
    }

    @PutMapping("/settings")
    public BlaulichtSettingsDto updateSettings(@RequestBody BlaulichtSettingsDto body) {
        if (body == null) {
            throw new IllegalArgumentException("Einstellungen fehlen.");
        }
        BlaulichtSettings saved = settings.update(body.toSettings());
        auditService.record("blaulicht.settings.update", describe(saved));
        polling.refreshOverview();
        return BlaulichtSettingsDto.from(saved);
    }

    private static String describe(BlaulichtSettings s) {
        return "presseportal=" + s.sources().presseportalEnabled()
                + ", nina=" + s.sources().ninaEnabled()
                + ", ort=" + s.localities().main()
                + ", nacht=" + s.briefing().nightStart() + "-" + s.briefing().nightEnd()
                + ", nachtrag=" + s.briefing().addendumAt();
    }
}
