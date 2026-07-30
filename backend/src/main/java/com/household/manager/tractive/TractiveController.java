package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Liefert den letzten bekannten Stand der Haustiere fuer die Kartenseite. */
@RestController
@RequestMapping("/v1/tractive")
@RequiredArgsConstructor
public class TractiveController {

    private final TractivePetService petService;
    private final TractiveWalkService walkService;
    private final TractivePollingService pollingService;

    @GetMapping("/pets")
    public List<TractivePetDto> pets() {
        return petService.listPets();
    }

    /**
     * Erzwingt einen sofortigen Abruf bei Tractive und liefert den frischen Stand.
     * Ohne diesen Endpunkt koennte das Frontend nur denselben Poller-Zwischenstand
     * erneut lesen — bei einem stillen Ausfall also endlos die leere Liste.
     *
     * <p>Ein Fehlschlag kommt als 400 (Anbindung deaktiviert / keine Tractive-Anmeldung)
     * oder 502 (Cloud-Fehler) mit Klartextmeldung zurueck, damit die Seite die Ursache
     * anzeigen kann statt nur "noch keine Daten".
     */
    @PostMapping("/pets/refresh")
    public List<TractivePetDto> refreshPets() {
        pollingService.refreshNow();
        return petService.listPets();
    }

    /** Spaziergaenge der letzten Tage, abgeleitet aus der Positionshistorie. */
    @GetMapping("/pets/{trackerId}/walks")
    public List<TractiveWalkDto> walks(@PathVariable String trackerId,
                                       @RequestParam(defaultValue = "7") int days) {
        return walkService.getWalks(trackerId, days);
    }
}
