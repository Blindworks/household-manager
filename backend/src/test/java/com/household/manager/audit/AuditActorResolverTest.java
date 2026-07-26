package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActorResolverTest {

    private final AuditActorResolver resolver = new AuditActorResolver();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        AuditActorContext.clear();
    }

    @Test
    void ohneAuthentifizierungIstDerAktorSystem() {
        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.SYSTEM, "system"));
    }

    @Test
    void anonymeAuthentifizierungIstSystem() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(resolver.currentActor().type()).isEqualTo(AuditActorType.SYSTEM);
    }

    @Test
    void nutzerSessionWirdAlsUserErkannt() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "bene", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")));

        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.USER, "bene"));
    }

    @Test
    void serviceTokenWirdUeberDieServiceAuthorityErkannt() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "tablet", null, AuthorityUtils.createAuthorityList("ROLE_KIOSK", "SERVICE")));

        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.SERVICE, "tablet"));
    }

    @Test
    void threadLocalOverrideGewinnt() {
        AuditActorContext.set(AuditActor.telegram(1234L));

        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.TELEGRAM, "TELEGRAM:1234"));
    }

    @Test
    void flowAktorWirdAlsSystemMitFlowIdErkannt() {
        AuditActorContext.set(AuditActor.flow(5L));

        assertThat(resolver.currentActor())
                .isEqualTo(new AuditActor(AuditActorType.SYSTEM, "FLOW:5"));
    }
}
