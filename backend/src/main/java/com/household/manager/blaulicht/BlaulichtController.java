package com.household.manager.blaulicht;

import com.household.manager.blaulicht.dto.BlaulichtBriefingDto;
import com.household.manager.blaulicht.dto.BlaulichtEventDto;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Blaulicht Bad Vilbel, nur lesend. Lesen ist über die generische {@code GET /v1/**}-Regel KIOSK-erlaubt
 * (Hub-Karte und Dialog am Wandtablet), keine eigene Security-Zeile.
 */
@RestController
@RequestMapping("/v1/blaulicht")
public class BlaulichtController {

    private final BlaulichtQueryService query;

    public BlaulichtController(BlaulichtQueryService query) {
        this.query = query;
    }

    /** Fälle, veröffentlicht ab {@code since} (Default 7 Tage), neueste zuerst, höchstens 200. */
    @GetMapping
    public List<BlaulichtEventDto> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false) BlaulichtCategory category,
            @RequestParam(required = false) String locality) {
        return query.list(since, category, locality);
    }

    /** Fälle der laufenden bzw. zuletzt vergangenen Nacht. */
    @GetMapping("/overnight")
    public List<BlaulichtEventDto> overnight() {
        return query.overnight();
    }

    /** Briefing und Nachtrag für die Hub-Karte. */
    @GetMapping("/briefing")
    public BlaulichtBriefingDto briefing() {
        return query.briefing();
    }
}
