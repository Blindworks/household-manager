package com.household.manager.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final PushSubscriptionRepository repository;
    private final WebPushClient webPushClient;
    private final ObjectMapper objectMapper;

    public void sendToAll(String title, String body) {
        List<PushSubscription> subscriptions;
        try {
            subscriptions = repository.findAll();
        } catch (Exception ex) {
            log.warn("Push-Subscriptions konnten nicht geladen werden: {}", ex.getMessage());
            return;
        }
        send(subscriptions, title, body);
    }

    public void sendToUser(Long userId, String title, String body) {
        List<PushSubscription> subscriptions;
        try {
            subscriptions = repository.findByUserId(userId);
        } catch (Exception ex) {
            log.warn("Push-Subscriptions fuer Nutzer {} konnten nicht geladen werden: {}", userId, ex.getMessage());
            return;
        }
        if (subscriptions.isEmpty()) {
            log.warn("Keine Push-Subscriptions fuer Nutzer {} — Nachricht verworfen", userId);
            return;
        }
        send(subscriptions, title, body);
    }

    private void send(List<PushSubscription> subscriptions, String title, String body) {
        if (subscriptions.isEmpty()) {
            log.debug("Keine Push-Subscriptions vorhanden — Nachricht verworfen");
            return;
        }
        String payload;
        try {
            payload = buildPayload(title, body);
        } catch (JsonProcessingException ex) {
            log.warn("Push-Payload nicht serialisierbar: {}", ex.getMessage());
            return;
        }
        for (PushSubscription subscription : subscriptions) {
            if (!sendTo(subscription, payload)) {
                break;
            }
        }
    }

    /** @return false wenn der Versand unterbrochen wurde und keine weiteren Geraete mehr bedient werden sollen. */
    private boolean sendTo(PushSubscription subscription, String payload) {
        try {
            int status = webPushClient.send(subscription, payload);
            if (status == 404 || status == 410) {
                repository.deleteById(subscription.getId());
                log.info("Push-Subscription '{}' verfallen (HTTP {}) — geloescht",
                        subscription.getDeviceLabel(), status);
            } else if (status >= 400) {
                log.warn("Push an '{}' fehlgeschlagen: HTTP {}", subscription.getDeviceLabel(), status);
            } else {
                subscription.setLastUsedAt(LocalDateTime.now());
                repository.save(subscription);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Push-Versand unterbrochen — breche restliche Geraete ab");
            return false;
        } catch (Exception ex) {
            log.warn("Push an '{}' fehlgeschlagen: {}", subscription.getDeviceLabel(), ex.toString());
        }
        return true;
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
