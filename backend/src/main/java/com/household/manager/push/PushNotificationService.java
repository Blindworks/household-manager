package com.household.manager.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.common.DeliveryResult;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Fire-and-forget-Versand von Web-Push-Nachrichten (Muster Telegram): wirft
 * nie, Fehler einzelner Geraete stoppen die anderen nicht. 404/410 vom
 * Push-Dienst loescht die verfallene Subscription (Selbstbereinigung — iOS
 * laesst Subscriptions bei laengerer Nichtnutzung verfallen).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private static final String NO_DEVICES = "keine Push-Geräte";

    private final PushSubscriptionRepository repository;
    private final WebPushClient webPushClient;
    private final ObjectMapper objectMapper;

    public DeliveryResult sendToAll(String title, String body) {
        List<PushSubscription> subscriptions;
        try {
            subscriptions = repository.findAll();
        } catch (Exception ex) {
            log.warn("Push-Subscriptions konnten nicht geladen werden: {}", ex.getMessage());
            return DeliveryResult.failed("Push-Geräte nicht ladbar: " + ex.getMessage());
        }
        return send(subscriptions, title, body);
    }

    public DeliveryResult sendToUser(Long userId, String title, String body) {
        List<PushSubscription> subscriptions;
        try {
            subscriptions = repository.findByUserId(userId);
        } catch (Exception ex) {
            log.warn("Push-Subscriptions fuer Nutzer {} konnten nicht geladen werden: {}", userId, ex.getMessage());
            return DeliveryResult.failed("Push-Geräte nicht ladbar: " + ex.getMessage());
        }
        if (subscriptions.isEmpty()) {
            log.warn("Keine Push-Subscriptions fuer Nutzer {} — Nachricht verworfen", userId);
            return DeliveryResult.failed(NO_DEVICES);
        }
        return send(subscriptions, title, body);
    }

    private DeliveryResult send(List<PushSubscription> subscriptions, String title, String body) {
        if (subscriptions.isEmpty()) {
            log.debug("Keine Push-Subscriptions vorhanden — Nachricht verworfen");
            return DeliveryResult.failed(NO_DEVICES);
        }
        String payload;
        try {
            payload = buildPayload(title, body);
        } catch (JsonProcessingException ex) {
            log.warn("Push-Payload nicht serialisierbar: {}", ex.getMessage());
            return DeliveryResult.failed("Push-Payload nicht serialisierbar: " + ex.getMessage());
        }
        DeliveryResult result = DeliveryResult.ok();
        for (PushSubscription subscription : subscriptions) {
            DeliveryResult single = sendTo(subscription, payload);
            result = result.and(single);
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        return result;
    }

    /** Nach einer Unterbrechung ist der Thread als interrupted markiert; die Schleife bricht dann ab. */
    private DeliveryResult sendTo(PushSubscription subscription, String payload) {
        try {
            int status = webPushClient.send(subscription, payload);
            if (status == 404 || status == 410) {
                repository.deleteById(subscription.getId());
                log.info("Push-Subscription '{}' verfallen (HTTP {}) — geloescht",
                        subscription.getDeviceLabel(), status);
            } else if (status >= 400) {
                log.warn("Push an '{}' fehlgeschlagen: HTTP {}", subscription.getDeviceLabel(), status);
                return DeliveryResult.failed("Push an '" + subscription.getDeviceLabel() + "': HTTP " + status);
            } else {
                subscription.setLastUsedAt(LocalDateTime.now());
                repository.save(subscription);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Push-Versand unterbrochen — breche restliche Geraete ab");
            return DeliveryResult.failed("Push-Versand unterbrochen");
        } catch (Exception ex) {
            log.warn("Push an '{}' fehlgeschlagen: {}", subscription.getDeviceLabel(), ex.toString());
            return DeliveryResult.failed("Push an '" + subscription.getDeviceLabel() + "': " + ex.getMessage());
        }
        return DeliveryResult.ok();
    }

    /** Payload im ngsw-Notification-Schema — der Angular Service Worker zeigt sie selbst an. */
    private String buildPayload(String title, String body) throws JsonProcessingException {
        Map<String, Object> notification = Map.of(
                "title", title,
                "body", body,
                "data", Map.of("onActionClick",
                        Map.of("default", Map.of("operation", "openWindow", "url", "/"))));
        return objectMapper.writeValueAsString(Map.of("notification", notification));
    }
}
