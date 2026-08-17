package com.household.manager.push;

import com.household.manager.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Push-API. Lesen faellt unter die generische GET-KIOSK-Regel, Schreiben unter
 * anyRequest -> MEMBER; eine eigene Security-Regel gibt es bewusst nicht
 * (SecurityRulesTest haelt beide Richtungen fest).
 */
@RestController
@RequestMapping("/v1/push")
@RequiredArgsConstructor
public class PushController {

    private final VapidKeyService vapidKeyService;
    private final PushSubscriptionService subscriptionService;
    private final PushNotificationService notificationService;
    private final CurrentUserService currentUserService;

    @GetMapping("/vapid-public-key")
    public PushDtos.PublicKeyResponse publicKey() {
        return new PushDtos.PublicKeyResponse(vapidKeyService.publicKey());
    }

    @GetMapping("/subscriptions")
    public List<PushDtos.SubscriptionResponse> mySubscriptions() {
        return subscriptionService.listForUser(currentUserService.requireUserId());
    }

    @PostMapping("/subscriptions")
    public PushDtos.SubscriptionResponse subscribe(@RequestBody PushDtos.SubscribeRequest request) {
        return subscriptionService.subscribe(currentUserService.requireUserId(), request);
    }

    @DeleteMapping("/subscriptions/{id}")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long id) {
        return subscriptionService.unsubscribe(currentUserService.requireUserId(), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Void> sendTest() {
        notificationService.sendToUser(currentUserService.requireUserId(),
                "Household Manager", "Testnachricht — Push funktioniert auf diesem Geraet.");
        return ResponseEntity.noContent().build();
    }
}
