package com.household.manager.tractive;

import com.household.manager.repository.TractiveAuthRepository;
import com.household.manager.tractive.dto.TractiveTokenDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TractiveAuthServiceTest {

    @Mock
    private TractiveApiClient apiClient;
    @Mock
    private TractiveAuthRepository repository;
    @InjectMocks
    private TractiveAuthService service;

    private TractiveAuth storedToken(LocalDateTime expiresAt) {
        return TractiveAuth.builder()
                .id(TractiveAuth.SINGLETON_ID)
                .accessToken("tok")
                .userId("u-1")
                .email("halter@example.com")
                .expiresAt(expiresAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void loginPersistsOnlyTheToken() {
        long expiryEpochSeconds = 1800000000L;
        when(apiClient.login("halter@example.com", "geheim"))
                .thenReturn(new TractiveTokenDto("u-1", "tok", expiryEpochSeconds));

        service.login("halter@example.com", "geheim");

        ArgumentCaptor<TractiveAuth> captor = ArgumentCaptor.forClass(TractiveAuth.class);
        verify(repository).save(captor.capture());
        TractiveAuth saved = captor.getValue();

        assertEquals(TractiveAuth.SINGLETON_ID, saved.getId());
        assertEquals("tok", saved.getAccessToken());
        assertEquals("u-1", saved.getUserId());
        assertEquals("halter@example.com", saved.getEmail());
        // Die Unix-Sekunde muss verlustfrei in lokale Zeit uebersetzt worden sein.
        assertEquals(expiryEpochSeconds,
                saved.getExpiresAt().atZone(ZoneId.systemDefault()).toEpochSecond());
    }

    @Test
    void validTokenIsReturned() {
        when(repository.findById(TractiveAuth.SINGLETON_ID))
                .thenReturn(Optional.of(storedToken(LocalDateTime.now().plusDays(1))));

        assertTrue(service.getValidToken().isPresent());
        assertEquals("tok", service.getValidToken().get().getAccessToken());
    }

    @Test
    void tokenExpiringWithinAnHourCountsAsInvalid() {
        when(repository.findById(TractiveAuth.SINGLETON_ID))
                .thenReturn(Optional.of(storedToken(LocalDateTime.now().plusMinutes(10))));

        assertTrue(service.getValidToken().isEmpty());
    }

    @Test
    void missingTokenCountsAsInvalid() {
        when(repository.findById(TractiveAuth.SINGLETON_ID)).thenReturn(Optional.empty());

        assertTrue(service.getValidToken().isEmpty());
        assertFalse(service.status().authenticated());
    }

    @Test
    void logoutDeletesTheToken() {
        service.logout();
        verify(repository).deleteById(TractiveAuth.SINGLETON_ID);
    }
}
