package com.household.manager.systemlog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.household.manager.flowengine.FlowRunRecorder;
import com.household.manager.model.entity.SystemLogEntry;
import com.household.manager.repository.SystemLogEntryRepository;
import com.household.manager.systemlog.SystemJobMonitor.JobStatusView;
import com.household.manager.systemlog.SystemLogDtos.FlowRef;
import com.household.manager.systemlog.SystemLogDtos.SystemLogEntryDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Lesepfad des Systemprotokolls fuer die Admin-Seite. */
@Service
@RequiredArgsConstructor
public class SystemLogQueryService {

    static final int DEFAULT_SIZE = 50;
    static final int MAX_SIZE = 200;
    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /** „Nur Probleme“ - einzige Definition. */
    static final List<SystemLogStatus> PROBLEM_STATUSES = List.of(SystemLogStatus.ERROR,
            SystemLogStatus.STOPPED, SystemLogStatus.RATE_LIMITED, SystemLogStatus.MISSED,
            SystemLogStatus.ABORTED, SystemLogStatus.WARN);

    private final SystemLogEntryRepository repository;
    private final SystemJobMonitor jobMonitor;
    private final FlowRunRecorder flowRunRecorder;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public Page<SystemLogEntryDto> entries(LocalDateTime from, LocalDateTime to, List<SystemLogKind> kinds,
                                           List<SystemLogStatus> statuses, Long flowId, String query,
                                           boolean problemsOnly, Integer page, Integer size) {
        LocalDateTime end = to != null ? to : LocalDateTime.now(clock);
        LocalDateTime start = from != null ? from : end.minus(DEFAULT_WINDOW);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("'from' liegt nach 'to'");
        }
        List<SystemLogStatus> statusFilter = problemsOnly ? PROBLEM_STATUSES : emptyToNull(statuses);
        int pageSize = size == null ? DEFAULT_SIZE : Math.min(Math.max(size, 1), MAX_SIZE);
        int pageNumber = page == null ? 0 : Math.max(page, 0);
        return repository.search(start, end, emptyToNull(kinds), statusFilter, flowId, likePattern(query),
                PageRequest.of(pageNumber, pageSize)).map(this::toDto);
    }

    public List<SystemLogEntryDto> run(String runId) {
        return repository.findByRunIdOrderByOccurredAtAscIdAsc(runId).stream().map(this::toDto).toList();
    }

    public List<JobStatusView> jobs() {
        return jobMonitor.statuses().stream()
                .sorted(Comparator.comparingInt((JobStatusView job) -> rank(job.state()))
                        .thenComparing(JobStatusView::jobKey))
                .toList();
    }

    public List<FlowRef> flows(LocalDateTime from, LocalDateTime to) {
        LocalDateTime end = to != null ? to : LocalDateTime.now(clock);
        LocalDateTime start = from != null ? from : end.minus(DEFAULT_WINDOW);
        return repository.findDistinctFlowIds(start, end).stream()
                .map(id -> new FlowRef(id, flowRunRecorder.flowName(id)))
                .sorted(Comparator.comparing(FlowRef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static int rank(JobState state) {
        return switch (state) {
            case FAILING -> 0;
            case OK -> 1;
            case INACTIVE -> 2;
        };
    }

    private static <T> List<T> emptyToNull(List<T> list) {
        return list == null || list.isEmpty() ? null : list;
    }

    /** Kleingeschriebenes LIKE-Muster; Prozent, Unterstrich und Backslash der Eingabe sind woertlich gemeint. */
    static String likePattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String escaped = query.trim().toLowerCase()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private SystemLogEntryDto toDto(SystemLogEntry e) {
        return new SystemLogEntryDto(e.getId(), e.getOccurredAt(), e.getKind(), e.getStatus(), e.getSource(),
                e.getTitle(), e.getMessage(), parseDetails(e.getDetails()), e.getFlowId(), e.getRunId(),
                e.getRepeatCount());
    }

    /** JSON, wenn es parst; sonst (z. B. Stacktrace-Text) ein Textknoten. Wirft nie. */
    private JsonNode parseDetails(String details) {
        if (details == null) {
            return null;
        }
        try {
            return objectMapper.readTree(details);
        } catch (Exception ex) {
            return TextNode.valueOf(details);
        }
    }
}
