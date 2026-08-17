package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direkte Absicherung der Principal-Faelle: CurrentUserService traegt die
 * "eigene Daten"-Sicherheitsgarantie fuer die Push-API, ist in
 * SecurityRulesTest aber nur gemockt.
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserServiceTest {

    @Mock
    private AppUserRepository repository;

    private CurrentUserService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new CurrentUserService(repository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void appUserPrincipalLiefertSeineId() {
        AppUser user = AppUser.builder().id(42L).username("user").displayName("Test")
                .passwordHash("x").role(UserRole.MEMBER).enabled(true).build();
        AppUserPrincipal principal = new AppUserPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(principal, null, "ROLE_MEMBER"));

        Optional<Long> result = service.currentUserId();

        assertThat(result).contains(42L);
    }

    @Test
    void einfachesUserDetailsFaelltAufRepositoryZurueck() {
        User principal = new User("user", "x", List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(principal, null, "ROLE_MEMBER"));
        AppUser user = AppUser.builder().id(7L).username("user").displayName("Test")
                .passwordHash("x").role(UserRole.MEMBER).enabled(true).build();
        when(repository.findByUsername("user")).thenReturn(Optional.of(user));

        Optional<Long> result = service.currentUserId();

        assertThat(result).contains(7L);
    }

    @Test
    void serviceTokenPrincipalAlsStringLiefertLeerUndFragtNieDasRepository() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "tablet-token", null, List.of(new SimpleGrantedAuthority("ROLE_KIOSK"))));

        Optional<Long> result = service.currentUserId();

        assertThat(result).isEmpty();
        verify(repository, never()).findByUsername(any());
    }

    @Test
    void keineAuthentifizierungLiefertLeer() {
        Optional<Long> result = service.currentUserId();

        assertThat(result).isEmpty();
    }

    @Test
    void requireUserIdWirftOhneSession() {
        assertThatThrownBy(() -> service.requireUserId())
                .isInstanceOf(IllegalStateException.class);
    }
}
