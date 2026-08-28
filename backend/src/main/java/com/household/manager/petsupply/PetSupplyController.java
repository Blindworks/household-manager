package com.household.manager.petsupply;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Vorrats-API fuer Toni (Futter, VomiSan-Tabletten). Der Vorrat wird ueber
 * seinen Schluessel adressiert; ein unbekannter Schluessel ergibt 404.
 * <p>
 * Lesen faellt unter die generische GET-KIOSK-Regel (Wandtablet sieht die
 * Kacheln), alle Schreibpfade unter anyRequest -> MEMBER; eine eigene
 * Security-Regel gibt es bewusst nicht (SecurityRulesTest haelt beide
 * Richtungen fest).
 */
@RestController
@RequestMapping("/v1/pet-supplies")
@RequiredArgsConstructor
public class PetSupplyController {

    private final PetSupplyService petSupplyService;

    @GetMapping
    public List<PetSupplyDtos.SupplyResponse> getSupplies() {
        return petSupplyService.getSupplies();
    }

    @GetMapping("/{key}/transactions")
    public List<PetSupplyDtos.TransactionResponse> getTransactions(
            @PathVariable String key,
            @RequestParam(defaultValue = "50") int limit) {
        return petSupplyService.getTransactions(key, limit);
    }

    @PostMapping("/{key}/purchases")
    public PetSupplyDtos.SupplyResponse recordPurchase(
            @PathVariable String key,
            @RequestBody PetSupplyDtos.PurchaseRequest request) {
        return petSupplyService.recordPurchase(key, request.amount(), request.note());
    }

    @PostMapping("/{key}/corrections")
    public PetSupplyDtos.SupplyResponse correctStock(
            @PathVariable String key,
            @RequestBody PetSupplyDtos.CorrectionRequest request) {
        return petSupplyService.correctStock(key, request.amountRemaining(), request.note());
    }

    @PutMapping("/{key}/target")
    public PetSupplyDtos.SupplyResponse updateTarget(
            @PathVariable String key,
            @RequestBody PetSupplyDtos.TargetRequest request) {
        return petSupplyService.updateTarget(key, request.targetAmount());
    }
}
