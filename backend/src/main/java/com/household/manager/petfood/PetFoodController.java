package com.household.manager.petfood;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Futtervorrats-API. Lesen faellt unter die generische GET-KIOSK-Regel
 * (Wandtablet sieht die Kachel), alle Schreibpfade unter anyRequest -> MEMBER;
 * eine eigene Security-Regel gibt es bewusst nicht (SecurityRulesTest haelt
 * beide Richtungen fest).
 */
@RestController
@RequestMapping("/v1/pet-food")
@RequiredArgsConstructor
public class PetFoodController {

    private final PetFoodService petFoodService;

    @GetMapping
    public PetFoodDtos.StatusResponse getStatus() {
        return petFoodService.getStatus();
    }

    @GetMapping("/transactions")
    public List<PetFoodDtos.TransactionResponse> getTransactions(
            @RequestParam(defaultValue = "50") int limit) {
        return petFoodService.getTransactions(limit);
    }

    @PostMapping("/purchases")
    public PetFoodDtos.StatusResponse recordPurchase(@RequestBody PetFoodDtos.PurchaseRequest request) {
        return petFoodService.recordPurchase(request.cans(), request.note());
    }

    @PostMapping("/corrections")
    public PetFoodDtos.StatusResponse correctStock(@RequestBody PetFoodDtos.CorrectionRequest request) {
        return petFoodService.correctStock(request.cansRemaining(), request.note());
    }

    @PutMapping("/target")
    public PetFoodDtos.StatusResponse updateTarget(@RequestBody PetFoodDtos.TargetRequest request) {
        return petFoodService.updateTarget(request.targetCans());
    }
}
