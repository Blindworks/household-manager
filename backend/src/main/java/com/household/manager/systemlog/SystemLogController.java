package com.household.manager.systemlog;

import com.household.manager.systemlog.SystemJobMonitor.JobStatusView;
import com.household.manager.systemlog.SystemLogDtos.FlowRef;
import com.household.manager.systemlog.SystemLogDtos.SystemLogEntryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** Systemprotokoll fuer die Admin-Seite. Rolle (ADMIN) steht in SecurityConfig, nicht hier. */
@RestController
@RequestMapping("/v1/admin/system-log")
@RequiredArgsConstructor
public class SystemLogController {

    private final SystemLogQueryService queryService;

    @GetMapping("/entries")
    public Page<SystemLogEntryDto> entries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) List<SystemLogKind> kind,
            @RequestParam(required = false) List<SystemLogStatus> status,
            @RequestParam(required = false) Long flowId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean problemsOnly,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return queryService.entries(from, to, kind, status, flowId, q, problemsOnly, page, size);
    }

    @GetMapping("/runs/{runId}")
    public List<SystemLogEntryDto> run(@PathVariable String runId) {
        return queryService.run(runId);
    }

    @GetMapping("/jobs")
    public List<JobStatusView> jobs() {
        return queryService.jobs();
    }

    @GetMapping("/flows")
    public List<FlowRef> flows(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return queryService.flows(from, to);
    }
}
