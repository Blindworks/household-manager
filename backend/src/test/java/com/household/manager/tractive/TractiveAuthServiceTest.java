package com.household.manager.tractive;

import com.household.manager.repository.TractiveAuthRepository;
import com.household.manager.tractive.dto.TractiveTokenDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
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
        when(apiClient.login("halter@example.com", "geheim"))
                .thenReturn(new TractiveTokenDto("u-1", "tok",
                        java.time.Instant.now().plusSeconds(86400).getEpochSecond()));

        service.login("halter@example.com", "geheim");

        verify(repository).save(argThat(auth ->
                auth.getId().equals(TractiveAuth.SINGLETON_ID)
                        && auth.getAccessToken().equals("tok")
                        && auth.getUserId().equals("u-1")
                        && auth.getEmail().equals("halter@example.com")));
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
