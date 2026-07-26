package com.household.manager.security;

import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ServiceToken;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.ServiceTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Service-Tokens der Maschinen-Clients. Der Klartext wird nur bei der
 * Erstellung zurueckgegeben; danach existiert nur noch der SHA-256-Hash.
 */
@Service
@RequiredArgsConstructor
public class ServiceTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServiceTokenRepository repository;

    /** Ergebnis der Token-Erstellung: Entity + einmalig sichtbarer Klartext. */
    public record CreatedToken(ServiceToken token, String plaintext) {
    }

    @Transactional
    public CreatedToken create(String name, UserRole role) {
        if (repository.existsByName(name)) {
            throw new DuplicateEntityException("Token-Name bereits vergeben: " + name);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String plaintext = "hm_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ServiceToken token = repository.save(ServiceToken.builder()
                .name(name)
                .tokenHash(TokenHasher.sha256Hex(plaintext))
                .role(role)
                .build());
        return new CreatedToken(token, plaintext);
    }

    /** Prueft einen Klartext-Token; bei Erfolg wird last_used_at fortgeschrieben. */
    @Transactional
    public Optional<ServiceToken> authenticate(String rawToken) {
        return repository.findByTokenHashAndEnabledTrue(TokenHasher.sha256Hex(rawToken))
                .map(token -> {
                    token.setLastUsedAt(LocalDateTime.now());
                    return repository.save(token);
                });
    }

    public List<ServiceToken> list() {
        return repository.findAll();
    }

    /** Widerruf = deaktivieren; die Zeile bleibt fuer Audit-Bezuege erhalten. */
    @Transactional
    public ServiceToken revoke(Long id) {
        ServiceToken token = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service-Token nicht gefunden: " + id));
        token.setEnabled(false);
        return repository.save(token);
    }
}
