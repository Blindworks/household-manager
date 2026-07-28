package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePetDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/pets")
    public List<TractivePetDto> pets() {
        return petService.listPets();
    }

    /** Spaziergaenge der letzten Tage, abgeleitet aus der Positionshistorie. */
    @GetMapping("/pets/{trackerId}/walks")
    public List<TractiveWalkDto> walks(@PathVariable String trackerId,
                                       @RequestParam(defaultValue = "7") int days) {
        return walkService.getWalks(trackerId, days);
    }
}
