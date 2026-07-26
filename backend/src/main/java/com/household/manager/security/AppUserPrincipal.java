package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import lombok.Getter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

/** UserDetails mit Anzeigename — Grundlage fuer /v1/auth/me. */
@Getter
public class AppUserPrincipal extends User {

    private final String displayName;

    public AppUserPrincipal(AppUser user) {
        super(user.getUsername(), user.getPasswordHash(), user.isEnabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
        this.displayName = user.getDisplayName();
    }
}
