package com.household.manager.push;

import com.household.manager.audit.AuditService;
import com.household.manager.model.entity.PushSubscription;
import com.household.manager.repository.PushSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionServiceTest {

    @Mock
    private PushSubscriptionRepository repository;
    @Mock
    private AuditService auditService;

    private PushSubscriptionService service() {
        return new PushSubscriptionService(repository, auditService);
    }

    private PushDtos.SubscribeRequest request() {
        return new PushDtos.SubscribeRequest("https://web.push.apple.com/abc", "p256dh-key", "auth-secret",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)");
    }

    @Test
    void subscribeCreatesNewSubscriptionWithDeviceLabel() {
        when(repository.findByEndpoint("https://web.push.apple.com/abc")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PushDtos.SubscriptionResponse response = service().subscribe(7L, request());

        assertEquals("iPhone", response.deviceLabel());
        verify(auditService).record(eq("push.subscribe"), anyString());
    }

    @Test
    void subscribeUpsertsExistingEndpointInsteadOfDuplicating() {
        PushSubscription existing = PushSubscription.builder().id(3L)
                .endpoint("https://web.push.apple.com/abc").userId(1L)
                .p256dhKey("old").authSecret("old").build();
        when(repository.findByEndpoint("https://web.push.apple.com/abc")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().subscribe(7L, request());

        ArgumentCaptor<PushSubscription> captor = ArgumentCaptor.forClass(PushSubscription.class);
        verify(repository).save(captor.capture());
        assertEquals(3L, captor.getValue().getId());
        assertEquals(7L, captor.getValue().getUserId());
        assertEquals("p256dh-key", captor.getValue().getP256dhKey());

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq("push.subscribe"), detailCaptor.capture());
        assertTrue(detailCaptor.getValue().contains("uebernommen"));
    }

    @Test
    void subscribeRejectsMissingFieldsAndNonHttpsEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> service().subscribe(7L,
                new PushDtos.SubscribeRequest("", "k", "a", null)));
        assertThrows(IllegalArgumentException.class, () -> service().subscribe(7L,
                new PushDtos.SubscribeRequest("http://insecure", "k", "a", null)));
    }

    @Test
    void subscribeRejectsOversizedKeysAndEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> service().subscribe(7L,
                new PushDtos.SubscribeRequest("https://web.push.apple.com/abc", "x".repeat(256), "a", null)));
        assertThrows(IllegalArgumentException.class, () -> service().subscribe(7L,
                new PushDtos.SubscribeRequest("https://" + "x".repeat(500), "k", "a", null)));
    }

    @Test
    void unsubscribeOnlyDeletesOwnSubscription() {
        when(repository.findByIdAndUserId(5L, 7L)).thenReturn(Optional.empty());

        assertFalse(service().unsubscribe(7L, 5L));
        verify(repository, never()).delete(any());
    }
}
