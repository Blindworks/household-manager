package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppUserDetailsServiceTest {

    @Mock
    private AppUserRepository repository;

    @InjectMocks
    private AppUserDetailsService service;

    @Test
    void mapptNutzerAufPrincipalMitRollenPrefix() {
        when(repository.findByUsername("bene")).thenReturn(Optional.of(AppUser.builder()
                .username("bene").displayName("Benedikt").passwordHash("hash")
                .role(UserRole.ADMIN).enabled(true).build()));

        UserDetails details = service.loadUserByUsername("bene");

        assertThat(details.getUsername()).isEqualTo("bene");
        assertThat(details.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        assertThat(details.isEnabled()).isTrue();
        assertThat(((AppUserPrincipal) details).getDisplayName()).isEqualTo("Benedikt");
    }

    @Test
    void unbekannterNutzerWirftUsernameNotFound() {
        when(repository.findByUsername("nix")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("nix"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void deaktivierterNutzerIstDisabled() {
        when(repository.findByUsername("alt")).thenReturn(Optional.of(AppUser.builder()
                .username("alt").displayName("Alt").passwordHash("hash")
                .role(UserRole.MEMBER).enabled(false).build()));

        assertThat(service.loadUserByUsername("alt").isEnabled()).isFalse();
    }
}
