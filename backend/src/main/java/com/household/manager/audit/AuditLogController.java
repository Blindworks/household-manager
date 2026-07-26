package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** Audit-Log-Einsicht (nur ADMIN). */
@RestController
@RequestMapping("/v1/admin/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    public record AuditEntryResponse(Long id, LocalDateTime timestamp, AuditActorType actorType,
                                     String actor, String action, String detail) {
    }

    @GetMapping
    public List<AuditEntryResponse> recent(@RequestParam(defaultValue = "100") int limit,
                                           @RequestParam(required = false) String actor) {
        return auditService.recent(limit, actor).stream()
                .map(entry -> new AuditEntryResponse(entry.getId(), entry.getTimestamp(),
                        entry.getActorType(), entry.getActor(), entry.getAction(), entry.getDetail()))
                .toList();
    }
}
