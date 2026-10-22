package com.household.manager.systemlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.flowengine.FlowRunRecorder;
import com.household.manager.model.entity.SystemLogEntry;
import com.household.manager.repository.SystemLogEntryRepository;
import com.household.manager.systemlog.SystemJobMonitor.JobStatusView;
import com.household.manager.systemlog.SystemLogDtos.SystemLogEntryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemLogQueryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    @Mock
    private SystemLogEntryRepository repository;
    @Mock
    private SystemJobMonitor jobMonitor;
    @Mock
    private FlowRunRecorder flowRunRecorder;

    private SystemLogQueryService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new SystemLogQueryService(repository, jobMonitor, flowRunRecorder, new ObjectMapper(), clock);
    }

    private void stubEmptyPage() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
    }

    @Test
    void problemsOnly_overridesStatusFilter() {
        stubEmptyPage();

        service.entries(null, null, null, List.of(SystemLogStatus.OK), null, null, true, null, null);

        verify(repository).search(any(), any(), isNull(), eq(SystemLogQueryService.PROBLEM_STATUSES),
                isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void sizeCappedAt200() {
        stubEmptyPage();

        service.entries(null, null, null, null, null, null, false, 0, 5000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(any(), any(), any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void defaultSizeIs50() {
        stubEmptyPage();

        service.entries(null, null, null, null, null, null, false, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(any(), any(), any(), any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue()).isEqualTo(PageRequest.of(0, 50));
    }

    @Test
    void defaultWindowIs24h() {
        stubEmptyPage();

        service.entries(null, null, null, null, null, null, false, null, null);

        verify(repository).search(eq(NOW.minusHours(24)), eq(NOW), any(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void fromAfterTo_isRejected() {
        assertThatThrownBy(() -> service.entries(NOW, NOW.minusHours(1), null, null, null, null, false, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyFilterListsAreForwardedAsNull() {
        stubEmptyPage();

        service.entries(null, null, List.of(), List.of(), null, "  ", false, null, null);

        verify(repository).search(any(), any(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void query_isLowercasedAndWildcardsEscaped() {
        assertThat(SystemLogQueryService.likePattern("Ab_%\\")).isEqualTo("%ab\\_\\%\\\\%");
    }

    @Test
    void jobs_sortedFailingOkInactive() {
        LocalDateTime t = NOW;
        when(jobMonitor.statuses()).thenReturn(List.of(
                new JobStatusView("b-inactive", JobState.INACTIVE, t, null, null, null, null),
                new JobStatusView("z-ok", JobState.OK, t, t, null, null, null),
                new JobStatusView("a-ok", JobState.OK, t, t, null, null, null),
                new JobStatusView("m-failing", JobState.FAILING, t, null, t, t, "x")));

        List<JobStatusView> jobs = service.jobs();

        assertThat(jobs).extracting(JobStatusView::jobKey)
                .containsExactly("m-failing", "a-ok", "z-ok", "b-inactive");
    }

    @Test
    void details_jsonIsParsed_plainTextBecomesTextNode() {
        SystemLogEntry json = entry(1L, "[{\"a\":1}]");
        SystemLogEntry text = entry(2L, "java.lang.IllegalStateException: boom\n\tat x");
        SystemLogEntry none = entry(3L, null);
        when(repository.search(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(json, text, none)));

        List<SystemLogEntryDto> result = service.entries(null, null, null, null, null, null, false, null, null)
                .getContent();

        assertThat(result.get(0).details().isArray()).isTrue();
        assertThat(result.get(1).details().isTextual()).isTrue();
        assertThat(result.get(1).details().asText()).startsWith("java.lang.IllegalStateException");
        assertThat(result.get(2).details()).isNull();
    }

    @Test
    void flows_resolveNamesAndSortByName() {
        when(repository.findDistinctFlowIds(any(), any())).thenReturn(List.of(7L, 3L));
        when(flowRunRecorder.flowName(7L)).thenReturn("Alpha");
        when(flowRunRecorder.flowName(3L)).thenReturn("Zeta");

        assertThat(service.flows(null, null)).extracting(f -> f.name()).containsExactly("Alpha", "Zeta");
    }

    private static SystemLogEntry entry(long id, String details) {
        SystemLogEntry e = new SystemLogEntry();
        e.setId(id);
        e.setOccurredAt(NOW);
        e.setKind(SystemLogKind.LOG);
        e.setStatus(SystemLogStatus.INFO);
        e.setSource("s");
        e.setTitle("t");
        e.setDetails(details);
        return e;
    }
}
