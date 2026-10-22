import { Component, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SystemLogService } from '../../services/system-log.service';
import {
  FlowRunStep, SystemLogEntry, SystemLogFlowRef, SystemLogJob, SystemLogKind, SystemLogQuery, SystemLogStatus
} from '../../models/system-log.model';
import {
  ALL_KINDS, ALL_STATUSES, RANGE_OPTIONS, RangeOption, StatusTone, formatSince, isStepList, kindIcon, kindLabel,
  rangeStart, statusLabel, statusTone, stepOutcomeLabel, stepTone, toLocalIso
} from '../../shared/system-log-view.util';

const PAGE_SIZE = 50;

/** Ein Abschnitt der aufgeklappten Zeile: ein Eintrag (Start oder Fortsetzung) mit seinen Steps. */
interface RunSection {
  entry: SystemLogEntry;
  steps: FlowRunStep[];
}

@Component({
  selector: 'app-admin-system-log',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './admin-system-log.component.html',
  styleUrl: './admin-system-log.component.scss'
})
export class AdminSystemLogComponent implements OnInit {
  private readonly systemLog = inject(SystemLogService);

  readonly kinds = ALL_KINDS;
  readonly statuses = ALL_STATUSES;
  readonly ranges = RANGE_OPTIONS;

  jobs: SystemLogJob[] = [];
  flows: SystemLogFlowRef[] = [];
  entries: SystemLogEntry[] = [];
  totalElements = 0;
  currentPage = 0;

  selectedKinds = new Set<SystemLogKind>(ALL_KINDS);
  statusFilter: SystemLogStatus | '' = '';
  flowFilter: number | null = null;
  rangeKey: RangeOption['key'] = 'H24';
  searchText = '';
  problemsOnly = false;

  okExpanded = false;
  inactiveExpanded = false;

  loading = false;
  errorMessage: string | null = null;

  expandedId: number | null = null;
  runSections: RunSection[] | null = null;
  runLoading = false;

  ngOnInit(): void {
    this.loadJobs();
    this.loadFlows();
    this.loadEntries(0);
  }

  reload(): void {
    this.loadJobs();
    this.loadFlows();
    this.loadEntries(0);
  }

  get failingJobs(): SystemLogJob[] {
    return this.jobs.filter(job => job.state === 'FAILING');
  }

  get okJobs(): SystemLogJob[] {
    return this.jobs.filter(job => job.state === 'OK');
  }

  get inactiveJobs(): SystemLogJob[] {
    return this.jobs.filter(job => job.state === 'INACTIVE');
  }

  get hasMore(): boolean {
    return this.entries.length < this.totalElements;
  }

  applyFilters(): void {
    this.loadEntries(0);
  }

  toggleKind(kind: SystemLogKind): void {
    if (this.selectedKinds.has(kind)) {
      this.selectedKinds.delete(kind);
    } else {
      this.selectedKinds.add(kind);
    }
    this.applyFilters();
  }

  setProblemsOnly(value: boolean): void {
    this.problemsOnly = value;
    this.applyFilters();
  }

  loadMore(): void {
    this.loadEntries(this.currentPage + 1);
  }

  toggleRow(entry: SystemLogEntry): void {
    if (this.expandedId === entry.id) {
      this.expandedId = null;
      this.runSections = null;
      return;
    }
    this.expandedId = entry.id;
    this.runSections = null;
    if (entry.kind === 'FLOW_RUN' && entry.runId) {
      this.loadRun(entry);
    }
  }

  stepsOf(entry: SystemLogEntry): FlowRunStep[] {
    return isStepList(entry.details) ? entry.details : [];
  }

  stacktraceOf(entry: SystemLogEntry): string | null {
    return typeof entry.details === 'string' ? entry.details : null;
  }

  sinceText(job: SystemLogJob): string {
    return formatSince(job.failingSince, new Date());
  }

  statusText(status: SystemLogStatus): string { return statusLabel(status); }
  toneOf(status: SystemLogStatus): StatusTone { return statusTone(status); }
  iconOf(kind: SystemLogKind): string { return kindIcon(kind); }
  kindText(kind: SystemLogKind): string { return kindLabel(kind); }
  outcomeText(outcome: string): string { return stepOutcomeLabel(outcome); }
  stepToneOf(outcome: string): StatusTone { return stepTone(outcome); }

  private loadRun(entry: SystemLogEntry): void {
    this.runLoading = true;
    this.systemLog.getRun(entry.runId as string).subscribe({
      next: run => {
        this.runLoading = false;
        if (this.expandedId === entry.id && run.length > 1) {
          this.runSections = run.map(e => ({ entry: e, steps: this.stepsOf(e) }));
        }
      },
      error: () => (this.runLoading = false)
    });
  }

  private loadJobs(): void {
    this.systemLog.getJobs().subscribe({
      next: jobs => (this.jobs = jobs),
      error: () => (this.errorMessage = 'Die Job-Tafel konnte nicht geladen werden.')
    });
  }

  private loadFlows(): void {
    this.systemLog.getFlows(this.fromIso()).subscribe({
      next: flows => (this.flows = flows),
      error: () => (this.flows = [])
    });
  }

  private loadEntries(pageNo: number): void {
    this.loading = true;
    this.errorMessage = null;
    this.systemLog.getEntries(this.buildQuery(pageNo)).subscribe({
      next: result => {
        this.loading = false;
        this.entries = pageNo === 0 ? result.content : [...this.entries, ...result.content];
        this.totalElements = result.totalElements;
        this.currentPage = result.page;
        if (pageNo === 0) {
          this.expandedId = null;
          this.runSections = null;
        }
      },
      error: () => {
        this.loading = false;
        this.errorMessage = 'Das Protokoll konnte nicht geladen werden.';
      }
    });
  }

  private fromIso(): string {
    return toLocalIso(rangeStart(this.rangeKey, new Date()));
  }

  private buildQuery(pageNo: number): SystemLogQuery {
    const query: SystemLogQuery = { from: this.fromIso(), page: pageNo, size: PAGE_SIZE };
    if (this.selectedKinds.size < ALL_KINDS.length) {
      query.kinds = ALL_KINDS.filter(kind => this.selectedKinds.has(kind));
    }
    if (this.statusFilter) { query.status = this.statusFilter; }
    if (this.flowFilter !== null) { query.flowId = this.flowFilter; }
    if (this.searchText.trim()) { query.q = this.searchText.trim(); }
    if (this.problemsOnly) { query.problemsOnly = true; }
    return query;
  }
}
