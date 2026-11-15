package com.household.manager.grocery;

import com.household.manager.model.entity.AppUser;
import com.household.manager.repository.AppUserRepository;
import com.household.manager.security.CurrentUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Einzige Definition der Besitzregeln: wer welche Produkte sieht (Filter {@code owner}), wer sie ändern darf
 * (eigene und Haushaltsprodukte) und wie Besitzer heißen. {@code ownerUserId} null = Haushalt.
 */
@Slf4j
@Component
public class GroceryOwners {

    public static final String HOUSEHOLD = "household";
    public static final String ALL = "all";
    static final String HOUSEHOLD_NAME = "Haushalt";

    private final AppUserRepository users;
    private final CurrentUserService currentUser;

    public GroceryOwners(AppUserRepository users, CurrentUserService currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    /**
     * Filter für {@code owner}: leer = eigene + Haushalt (braucht eine Nutzer-Session), {@code household},
     * {@code all} oder eine Nutzer-Id.
     */
    public Predicate<Long> filter(String owner) {
        if (owner == null || owner.isBlank()) {
            Long me = currentUser.requireUserId();
            return ownerId -> ownerId == null || ownerId.equals(me);
        }
        String value = owner.trim();
        if (HOUSEHOLD.equalsIgnoreCase(value)) {
            return Objects::isNull;
        }
        if (ALL.equalsIgnoreCase(value)) {
            return ownerId -> true;
        }
        try {
            long userId = Long.parseLong(value);
            return ownerId -> ownerId != null && ownerId == userId;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Unbekannter Besitzer-Filter: " + value);
        }
    }

    public Optional<Long> currentUserId() {
        return currentUser.currentUserId();
    }

    public Long requireUserId() {
        return currentUser.requireUserId();
    }

    public static boolean isEditable(Long ownerUserId, Optional<Long> me) {
        return ownerUserId == null || me.map(ownerUserId::equals).orElse(false);
    }

    /** Wirft 403, wenn das Produkt einer anderen Person gehört. Ohne Nutzer-Session 400. */
    public void requireEditable(Long ownerUserId) {
        if (ownerUserId == null) {
            return;
        }
        if (!ownerUserId.equals(requireUserId())) {
            throw new AccessDeniedException("Produkte anderer Personen lassen sich nur ansehen");
        }
    }

    /** Anzeigenamen aller Nutzer; leer, wenn die Nutzer nicht lesbar sind (wirft nie). */
    public Map<Long, String> displayNames() {
        Map<Long, String> names = new HashMap<>();
        try {
            for (AppUser user : users.findAll()) {
                names.put(user.getId(), user.getDisplayName());
            }
        } catch (RuntimeException e) {
            log.warn("Nutzernamen nicht lesbar: {}", e.getMessage());
        }
        return names;
    }

    public static String ownerName(Long ownerUserId, Map<Long, String> names) {
        if (ownerUserId == null) {
            return HOUSEHOLD_NAME;
        }
        String name = names.get(ownerUserId);
        return name == null || name.isBlank() ? "Nutzer " + ownerUserId : name;
    }
}
