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
    private final AuditEntryDescriber describer;

    /** Rohwerte plus lesbare Fassung ({@code …Label}) — die Rohwerte bleiben für Filter und Fehlersuche erhalten. */
    public record AuditEntryResponse(Long id, LocalDateTime timestamp, AuditActorType actorType,
                                     String actor, String action, String detail,
                                     String actorLabel, String actionLabel, String detailLabel) {
    }

    @GetMapping
    public List<AuditEntryResponse> recent(@RequestParam(defaultValue = "100") int limit,
                                           @RequestParam(required = false) String actor) {
        return describer.describe(auditService.recent(limit, actor)).stream()
                .map(described -> new AuditEntryResponse(described.log().getId(), described.log().getTimestamp(),
                        described.log().getActorType(), described.log().getActor(), described.log().getAction(),
                        described.log().getDetail(), described.actorLabel(), described.actionLabel(),
                        described.detailLabel()))
                .toList();
    }
}
