package com.household.manager.security;

import com.household.manager.exception.DuplicateEntityException;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Verwaltung der Nutzerkonten (Admin-API + Bootstrap des ersten Admins). */
@Service
@RequiredArgsConstructor
public class AppUserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public List<AppUser> list() {
        return repository.findAll();
    }

    @Transactional
    public AppUser create(String username, String displayName, String password, UserRole role) {
        if (repository.existsByUsername(username)) {
            throw new DuplicateEntityException("Benutzername bereits vergeben: " + username);
        }
        return repository.save(AppUser.builder()
                .username(username)
                .displayName(displayName)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .build());
    }

    @Transactional
    public AppUser update(Long id, String displayName, UserRole role, boolean enabled) {
        AppUser user = getOrThrow(id);
        boolean losesAdmin = user.getRole() == UserRole.ADMIN && (role != UserRole.ADMIN || !enabled);
        if (losesAdmin && countOtherActiveAdmins(user) == 0) {
            throw new IllegalStateException("Der letzte aktive Admin kann nicht deaktiviert oder degradiert werden.");
        }
        user.setDisplayName(displayName);
        user.setRole(role);
        user.setEnabled(enabled);
        return repository.save(user);
    }

    @Transactional
    public void setPassword(Long id, String password) {
        AppUser user = getOrThrow(id);
        user.setPasswordHash(passwordEncoder.encode(password));
        repository.save(user);
    }

    /**
     * Legt beim ersten Start einen Admin an. Liefert das generierte
     * Zufallspasswort, falls keines konfiguriert war (fuer die Log-Ausgabe).
     */
    @Transactional
    public Optional<String> bootstrapAdmin(String configuredPassword) {
        if (repository.count() > 0) {
            return Optional.empty();
        }
        boolean generate = !StringUtils.hasText(configuredPassword);
        String password = generate ? generatePassword() : configuredPassword;
        repository.save(AppUser.builder()
                .username("admin")
                .displayName("Administrator")
                .passwordHash(passwordEncoder.encode(password))
                .role(UserRole.ADMIN)
                .build());
        return generate ? Optional.of(password) : Optional.empty();
    }

    private AppUser getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Nutzer nicht gefunden: " + id));
    }

    private long countOtherActiveAdmins(AppUser excluded) {
        return repository.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN && u.isEnabled()
                        && !u.getId().equals(excluded.getId()))
                .count();
    }

    private String generatePassword() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
