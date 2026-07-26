package com.household.manager.audit;

import com.household.manager.model.entity.AuditLog;
import com.household.manager.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Zentrales Audit-Log. record() wirft nie — ein Audit-Fehler darf die
 * fachliche Aktion nicht brechen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository repository;
    private final AuditActorResolver actorResolver;

    public void record(String action, String detail) {
        record(actorResolver.currentActor(), action, detail);
    }

    public void record(AuditActor actor, String action, String detail) {
        try {
            repository.save(AuditLog.builder()
                    .actorType(actor.type())
                    .actor(actor.name())
                    .action(action)
                    .detail(detail)
                    .build());
        } catch (Exception ex) {
            log.warn("Audit-Eintrag fehlgeschlagen ({} / {}): {}", action, detail, ex.getMessage());
        }
    }

    public List<AuditLog> recent(int limit, String actorFilter) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        return StringUtils.hasText(actorFilter)
                ? repository.findByActorOrderByTimestampDesc(actorFilter, page)
                : repository.findByOrderByTimestampDesc(page);
    }
}
