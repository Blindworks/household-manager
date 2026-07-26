package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePetDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Liefert den letzten bekannten Stand der Haustiere fuer die Kartenseite. */
@RestController
@RequestMapping("/v1/tractive")
@RequiredArgsConstructor
public class TractiveController {

    private final TractivePetService petService;

    @GetMapping("/pets")
    public List<TractivePetDto> pets() {
        return petService.listPets();
    }
}
