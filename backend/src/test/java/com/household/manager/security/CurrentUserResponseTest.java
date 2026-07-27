package com.household.manager.security;

import com.household.manager.model.entity.AppUser;
import com.household.manager.model.entity.UserRole;
import com.household.manager.security.dto.CurrentUserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserResponseTest {

    @Test
    void nutzerSessionLiefertAnzeigenameUndRolle() {
        AppUserPrincipal principal = new AppUserPrincipal(AppUser.builder()
                .username("bene").displayName("Benedikt").passwordHash("x")
                .role(UserRole.ADMIN).enabled(true).build());
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        CurrentUserResponse response = CurrentUserResponse.from(auth);

        assertThat(response.username()).isEqualTo("bene");
        assertThat(response.displayName()).isEqualTo("Benedikt");
        assertThat(response.role()).isEqualTo("ADMIN");
        assertThat(response.mustChangePassword()).isFalse();
    }

    @Test
    void nutzerSessionLiefertDieEigeneNutzerId() {
        AppUserPrincipal principal = new AppUserPrincipal(AppUser.builder()
                .id(5L).username("bene").displayName("Benedikt").passwordHash("x")
                .role(UserRole.ADMIN).enabled(true).build());
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        assertThat(CurrentUserResponse.from(auth).id()).isEqualTo(5L);
    }

    @Test
    void serviceTokenLiefertKeineIdUndWirftNicht() {
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                "tablet", null, AuthorityUtils.createAuthorityList("ROLE_KIOSK", "SERVICE"));

        assertThat(CurrentUserResponse.from(auth).id()).isNull();
    }

    @Test
    void pflichtwechselFlagWirdDurchgereicht() {
        AppUserPrincipal principal = new AppUserPrincipal(AppUser.builder()
                .username("admin").displayName("Administrator").passwordHash("x")
                .role(UserRole.ADMIN).enabled(true).mustChangePassword(true).build());
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        assertThat(CurrentUserResponse.from(auth).mustChangePassword()).isTrue();
    }

    @Test
    void serviceTokenLiefertTokenNamenAlsAnzeigename() {
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                "tablet", null, AuthorityUtils.createAuthorityList("ROLE_KIOSK", "SERVICE"));

        CurrentUserResponse response = CurrentUserResponse.from(auth);

        assertThat(response.username()).isEqualTo("tablet");
        assertThat(response.displayName()).isEqualTo("tablet");
        assertThat(response.role()).isEqualTo("KIOSK");
        assertThat(response.mustChangePassword()).isFalse();
    }
}
