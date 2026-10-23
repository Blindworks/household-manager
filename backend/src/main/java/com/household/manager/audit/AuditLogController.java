package com.household.manager.audit;

import com.household.manager.model.entity.AuditActorType;
import com.household.manager.model.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Audit-Log-Einsicht (nur ADMIN). */
@RestController
@RequestMapping("/v1/admin/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;

    private final AuditService auditService;
    private final AuditEntryDescriber describer;

    /** Rohwerte plus lesbare Fassung ({@code …Label}) — die Rohwerte bleiben für Filter und Fehlersuche erhalten. */
    public record AuditEntryResponse(Long id, LocalDateTime timestamp, AuditActorType actorType,
                                     String actor, String action, String detail,
                                     String actorLabel, String actionLabel, String detailLabel) {
    }

    public record AuditPageResponse(List<AuditEntryResponse> content, long totalElements, int page, int size) {
    }

    public record AuditActionOption(String action, String label) {
    }

    /**
     * Seitenweise Suche, neueste zuerst. {@code q} durchsucht Rohwerte und — über
     * {@link AuditEntryDescriber#searchHints} — auch Flow-, Geräte- und Entitätsnamen.
     */
    @GetMapping
    public AuditPageResponse search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(name = "actorKind", required = false) Set<AuditActorKind> actorKinds,
            @RequestParam(name = "action", required = false) Set<String> actions,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("Zeitraum ungültig: von liegt nach bis.");
        }
        int pageNumber = Math.max(0, page);
        int pageSize = Math.max(1, Math.min(size, MAX_SIZE));
        AuditLogFilter filter = new AuditLogFilter(from, to, actorKinds, actions, q);
        Page<AuditLog> result =
                auditService.search(filter, describer.searchHints(q), pageNumber, pageSize);
        List<AuditEntryResponse> content = describer.describe(result.getContent()).stream()
                .map(AuditLogController::toResponse)
                .toList();
        return new AuditPageResponse(content, result.getTotalElements(), pageNumber, pageSize);
    }

    /** Alle vorkommenden Aktionen mit lesbarer Bezeichnung, für die Auswahlliste. */
    @GetMapping("/actions")
    public List<AuditActionOption> actions() {
        return auditService.actions().stream()
                .map(action -> new AuditActionOption(action, AuditEntryDescriber.actionLabel(action)))
                .sorted(Comparator.comparing(AuditActionOption::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static AuditEntryResponse toResponse(AuditEntryDescriber.DescribedEntry described) {
        AuditLog log = described.log();
        return new AuditEntryResponse(log.getId(), log.getTimestamp(), log.getActorType(), log.getActor(),
                log.getAction(), log.getDetail(), described.actorLabel(), described.actionLabel(),
                described.detailLabel());
    }
}
