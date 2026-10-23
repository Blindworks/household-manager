package com.household.manager.audit;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Filter der Audit-Log-Suche. {@code null} oder leer heißt jeweils „nicht einschränken“.
 */
public record AuditLogFilter(LocalDateTime from, LocalDateTime to, Set<AuditActorKind> actorKinds,
                             Set<String> actions, String query) {
}
