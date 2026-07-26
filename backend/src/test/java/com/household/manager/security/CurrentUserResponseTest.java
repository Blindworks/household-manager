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
    }

    @Test
    void serviceTokenLiefertTokenNamenAlsAnzeigename() {
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                "tablet", null, AuthorityUtils.createAuthorityList("ROLE_KIOSK", "SERVICE"));

        CurrentUserResponse response = CurrentUserResponse.from(auth);

        assertThat(response.username()).isEqualTo("tablet");
        assertThat(response.displayName()).isEqualTo("tablet");
        assertThat(response.role()).isEqualTo("KIOSK");
    }
}
