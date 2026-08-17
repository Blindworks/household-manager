package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Liefert die App-User-Id der aktuellen Session. Leer bei Service-Tokens und
 * ausserhalb eines Request-Kontexts.
 */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final AppUserRepository repository;

    public Optional<Long> currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            return Optional.ofNullable(principal.getId());
        }
        if (authentication.getPrincipal() instanceof UserDetails details) {
            return repository.findByUsername(details.getUsername()).map(AppUser::getId);
        }
        return Optional.empty();
    }

    /** IllegalStateException wird vom GlobalExceptionHandler als 400 abgebildet. */
    public Long requireUserId() {
        return currentUserId().orElseThrow(() ->
                new IllegalStateException("Diese Aktion braucht eine Nutzer-Session (kein Service-Token)"));
    }
}
