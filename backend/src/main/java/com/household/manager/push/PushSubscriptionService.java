package com.household.manager.push;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Verwaltung der Web-Push-Subscriptions. Anmelden ist ein Upsert per Endpoint —
 * erneutes Abonnieren desselben Geraets erzeugt keine Dublette, sondern
 * aktualisiert Schluessel und Besitzer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushSubscriptionService {

    private static final int MAX_ENDPOINT_LENGTH = 500;

    private final PushSubscriptionRepository repository;
    private final AuditService auditService;

    @Transactional
    public PushDtos.SubscriptionResponse subscribe(Long userId, PushDtos.SubscribeRequest request) {
        String endpoint = validated(request);
        PushSubscription subscription = repository.findByEndpoint(endpoint)
                .orElseGet(() -> PushSubscription.builder().endpoint(endpoint).build());
        subscription.setUserId(userId);
        subscription.setP256dhKey(request.p256dh().trim());
        subscription.setAuthSecret(request.auth().trim());
        subscription.setDeviceLabel(deviceLabel(request.userAgent()));
        PushSubscription saved = repository.save(subscription);
        auditService.record("push.subscribe", "Geraet: " + saved.getDeviceLabel());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PushDtos.SubscriptionResponse> listForUser(Long userId) {
        return repository.findByUserId(userId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public boolean unsubscribe(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
                .map(subscription -> {
                    repository.delete(subscription);
                    auditService.record("push.unsubscribe", "Geraet: " + subscription.getDeviceLabel());
                    return true;
                })
                .orElse(false);
    }

    private String validated(PushDtos.SubscribeRequest request) {
        if (request == null || isBlank(request.endpoint()) || isBlank(request.p256dh()) || isBlank(request.auth())) {
            throw new IllegalArgumentException("endpoint, p256dh und auth sind Pflichtfelder");
        }
        String endpoint = request.endpoint().trim();
        if (!endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("endpoint muss eine https-URL sein");
        }
        if (endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw new IllegalArgumentException("endpoint ist zu lang (max. " + MAX_ENDPOINT_LENGTH + " Zeichen)");
        }
        return endpoint;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Grobe, rein kosmetische Geraetebezeichnung aus dem User-Agent. */
    private String deviceLabel(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unbekanntes Geraet";
        }
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Android")) {
            return "Android-Geraet";
        }
        if (userAgent.contains("Macintosh")) {
            return "Mac";
        }
        if (userAgent.contains("Windows")) {
            return "Windows-PC";
        }
        return "Unbekanntes Geraet";
    }

    private PushDtos.SubscriptionResponse toResponse(PushSubscription subscription) {
        return new PushDtos.SubscriptionResponse(
                subscription.getId(),
                subscription.getDeviceLabel(),
                subscription.getCreatedAt(),
                subscription.getLastUsedAt(),
                subscription.getEndpoint());
    }
}
