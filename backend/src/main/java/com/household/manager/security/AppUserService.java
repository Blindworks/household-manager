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

import java.util.List;

/** Verwaltung der Nutzerkonten (Admin-API + Bootstrap des ersten Admins). */
@Service
@RequiredArgsConstructor
public class AppUserService {

    /** Bekanntes Bootstrap-Passwort; must_change_password erzwingt den Wechsel beim ersten Login. */
    static final String BOOTSTRAP_PASSWORD = "changeit";

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

    /**
     * Bewusster Trade-off: ein Admin darf sich selbst degradieren oder
     * deaktivieren, solange ein anderer aktiver Admin existiert — das ist
     * beabsichtigt. Die eigene Session verliert die Berechtigung dann ueber
     * den DisabledUserSessionFilter (deaktiviert) bzw. beim naechsten
     * Request ueber die neu geladene Rolle (degradiert), nicht sofort.
     */
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
     * Legt beim ersten Start einen Admin mit dem bekannten Passwort
     * "changeit" an; must_change_password erzwingt den Wechsel beim ersten
     * Login. Liefert true, wenn der Admin angelegt wurde (fuer die Log-Ausgabe).
     */
    @Transactional
    public boolean bootstrapAdmin() {
        if (repository.count() > 0) {
            return false;
        }
        repository.save(AppUser.builder()
                .username("admin")
                .displayName("Administrator")
                .passwordHash(passwordEncoder.encode(BOOTSTRAP_PASSWORD))
                .role(UserRole.ADMIN)
                .mustChangePassword(true)
                .build());
        return true;
    }

    /**
     * Selbst-Passwortwechsel des angemeldeten Nutzers; verlangt das aktuelle
     * Passwort und loescht das must_change_password-Flag.
     */
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        AppUser user = repository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Nutzer nicht gefunden: " + username));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Aktuelles Passwort ist falsch.");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        repository.save(user);
    }

    private AppUser getOrThrow(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Nutzer nicht gefunden: " + id));
    }

    /**
     * Bewusster Trade-off: dieser Zaehl-Check ist nicht race-frei — zwei
     * parallele Degradierungen der letzten beiden aktiven Admins koennten
     * beide diesen Check passieren, da keine Sperre (z. B. SELECT ... FOR
     * UPDATE) verwendet wird. Bei Haushaltsgroesse (wenige Admins, seltene
     * gleichzeitige Verwaltungsaktionen) unkritisch.
     */
    private long countOtherActiveAdmins(AppUser excluded) {
        return repository.findAll().stream()
                .filter(u -> u.getRole() == UserRole.ADMIN && u.isEnabled()
                        && !u.getId().equals(excluded.getId()))
                .count();
    }
}
