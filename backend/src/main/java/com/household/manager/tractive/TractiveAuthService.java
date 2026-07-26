package com.household.manager.tractive;

import com.household.manager.repository.TractiveAuthRepository;
import com.household.manager.tractive.dto.TractiveAuthStatusDto;
import com.household.manager.tractive.dto.TractiveTokenDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Verwaltet das Tractive-Zugangstoken. Tractive kennt kein Refresh-Token: laeuft das
 * Token ab, ist ein erneuter Login noetig. Zugangsdaten werden nie persistiert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractiveAuthService {

    /** Sicherheitsabstand: Token unter dieser Restlaufzeit gelten als abgelaufen. */
    private static final Duration EXPIRY_MARGIN = Duration.ofHours(1);

    private final TractiveApiClient apiClient;
    private final TractiveAuthRepository repository;

    @Transactional
    public TractiveAuthStatusDto login(String email, String password) {
        TractiveTokenDto token = apiClient.login(email, password);
        // Tractive liefert eine Unix-Sekunde; hier auf lokale Zeit gebracht, damit die
        // Speicherung zu allen anderen Zeitstempeln dieses Schemas passt.
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(token.expiresAt()), ZoneId.systemDefault());
        repository.save(TractiveAuth.builder()
                .id(TractiveAuth.SINGLETON_ID)
                .accessToken(token.accessToken())
                .userId(token.userId())
                .email(email)
                .expiresAt(expiresAt)
                .updatedAt(LocalDateTime.now())
                .build());
        log.info("Tractive-Login erfolgreich, Token gueltig bis {}", expiresAt);
        return new TractiveAuthStatusDto(true, email, expiresAt);
    }

    /** Das gespeicherte Token, sofern vorhanden und ausreichend lange gueltig. */
    @Transactional(readOnly = true)
    public Optional<TractiveAuth> getValidToken() {
        return repository.findById(TractiveAuth.SINGLETON_ID)
                .filter(this::isUsable);
    }

    @Transactional(readOnly = true)
    public TractiveAuthStatusDto status() {
        return repository.findById(TractiveAuth.SINGLETON_ID)
                .map(auth -> new TractiveAuthStatusDto(
                        isUsable(auth), auth.getEmail(), auth.getExpiresAt()))
                .orElse(new TractiveAuthStatusDto(false, null, null));
    }

    @Transactional
    public void logout() {
        repository.deleteById(TractiveAuth.SINGLETON_ID);
    }

    /** Token gilt nur als brauchbar, solange es den Sicherheitsabstand ueberdauert. */
    private boolean isUsable(TractiveAuth auth) {
        return auth.getExpiresAt().isAfter(LocalDateTime.now().plus(EXPIRY_MARGIN));
    }
}
