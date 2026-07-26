package com.household.manager.audit;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Loest den aktuellen Aktor auf: ThreadLocal-Override > SecurityContext > SYSTEM. */
@Component
public class AuditActorResolver {

    /** Muss mit SecurityConfig.SERVICE_AUTHORITY uebereinstimmen (kein Import — Zykusvermeidung). */
    static final String SERVICE_AUTHORITY = "SERVICE";

    public AuditActor currentActor() {
        AuditActor override = AuditActorContext.get();
        if (override != null) {
            return override;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return AuditActor.system();
        }
        Set<String> authorities = AuthorityUtils.authorityListToSet(auth.getAuthorities());
        if (authorities.contains(SERVICE_AUTHORITY)) {
            return AuditActor.service(auth.getName());
        }
        return AuditActor.user(auth.getName());
    }
}
