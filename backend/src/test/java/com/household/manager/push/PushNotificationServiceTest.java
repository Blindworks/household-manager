package com.household.manager.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock
    private PushSubscriptionRepository repository;
    @Mock
    private WebPushClient webPushClient;

    private PushNotificationService service() {
        return new PushNotificationService(repository, webPushClient, new ObjectMapper());
    }

    private PushSubscription subscription(long id) {
        return PushSubscription.builder().id(id).userId(1L)
                .endpoint("https://push.example/" + id).p256dhKey("k").authSecret("a")
                .deviceLabel("iPhone").build();
    }

    @Test
    void deletesExpiredSubscriptionOn410() throws Exception {
        when(repository.findAll()).thenReturn(List.of(subscription(1)));
        when(webPushClient.send(any(), anyString())).thenReturn(410);

        service().sendToAll("Titel", "Text");

        verify(repository).deleteById(1L);
    }

    @Test
    void oneFailingDeviceDoesNotStopTheOthers() throws Exception {
        when(repository.findAll()).thenReturn(List.of(subscription(1), subscription(2)));
        when(webPushClient.send(any(), anyString()))
                .thenThrow(new RuntimeException("kaputt"))
                .thenReturn(201);

        assertDoesNotThrow(() -> service().sendToAll("Titel", "Text"));

        verify(webPushClient, times(2)).send(any(), anyString());
    }

    @Test
    void payloadFollowsNgswNotificationSchema() throws Exception {
        when(repository.findAll()).thenReturn(List.of(subscription(1)));
        when(webPushClient.send(any(), anyString())).thenReturn(201);

        service().sendToAll("Titel", "Text");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(webPushClient).send(any(), payload.capture());
        assertTrue(payload.getValue().contains("\"notification\""));
        assertTrue(payload.getValue().contains("\"title\":\"Titel\""));
        assertTrue(payload.getValue().contains("\"openWindow\""));
    }

    @Test
    void noSubscriptionsMeansNoSendAndNoError() {
        when(repository.findByUserId(9L)).thenReturn(List.of());

        assertDoesNotThrow(() -> service().sendToUser(9L, "Titel", "Text"));

        verifyNoInteractions(webPushClient);
    }

    @Test
    void deletesExpiredSubscriptionOn404() throws Exception {
        when(repository.findAll()).thenReturn(List.of(subscription(1)));
        when(webPushClient.send(any(), anyString())).thenReturn(404);

        service().sendToAll("Titel", "Text");

        verify(repository).deleteById(1L);
    }

    @Test
    void successfulSendUpdatesLastUsedAt() throws Exception {
        PushSubscription subscription = subscription(1);
        when(repository.findAll()).thenReturn(List.of(subscription));
        when(webPushClient.send(any(), anyString())).thenReturn(201);

        service().sendToAll("Titel", "Text");

        ArgumentCaptor<PushSubscription> saved = ArgumentCaptor.forClass(PushSubscription.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().getLastUsedAt() != null);
    }

    @Test
    void transientErrorDoesNotDeleteSubscription() throws Exception {
        when(repository.findAll()).thenReturn(List.of(subscription(1)));
        when(webPushClient.send(any(), anyString())).thenReturn(429);

        service().sendToAll("Titel", "Text");

        verify(repository, never()).deleteById(any());
    }
}
