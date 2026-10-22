import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
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
  imports: [FormsModule, DatePipe, NgTemplateOutlet],
  templateUrl: './admin-system-log.component.html',
  styleUrl: './admin-system-log.component.scss'
})
export class AdminSystemLogComponent implements OnInit, OnDestroy {
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
  jobsError: string | null = null;
  entriesError: string | null = null;

  /** Angewendeter Filterstand inkl. eingefrorenem from; Seitenabrufe nutzen immer ihn, nie den Formularstand. */
  private applied: SystemLogQuery = {};
  private entriesSub?: Subscription;

  expandedId: number | null = null;
  runSections: RunSection[] | null = null;
  runLoading = false;

  ngOnInit(): void {
    this.reload();
  }

  ngOnDestroy(): void {
    this.entriesSub?.unsubscribe();
  }

  reload(): void {
    this.loadJobs();
    this.applyFilters();
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

  /** Friert den Formularstand samt Zeitfenster ein und lädt Seite 0 neu. */
  applyFilters(): void {
    this.applied = this.buildQuery();
    this.loadFlows(this.applied.from);
    this.loadEntries(0);
  }

  toggleKind(kind: SystemLogKind): void {
    if (this.selectedKinds.has(kind)) {
      if (this.selectedKinds.size === 1) {
        return; // mindestens eine Art bleibt gewählt
      }
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
    this.runLoading = false;
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
        if (this.expandedId !== entry.id) { return; }
        this.runLoading = false;
        if (run.length > 1) {
          this.runSections = run.map(e => ({ entry: e, steps: this.stepsOf(e) }));
        }
      },
      error: () => {
        if (this.expandedId === entry.id) { this.runLoading = false; }
      }
    });
  }

  private loadJobs(): void {
    this.systemLog.getJobs().subscribe({
      next: jobs => { this.jobs = jobs; this.jobsError = null; },
      error: () => (this.jobsError = 'Die Job-Tafel konnte nicht geladen werden.')
    });
  }

  private loadFlows(from?: string): void {
    this.systemLog.getFlows(from).subscribe({
      next: flows => (this.flows = flows),
      error: () => (this.flows = [])
    });
  }

  private loadEntries(pageNo: number): void {
    if (pageNo === 0) {
      this.entriesSub?.unsubscribe();
    }
    const snapshot = this.applied;
    this.loading = true;
    this.entriesError = null;
    const sub = this.systemLog.getEntries({ ...snapshot, page: pageNo, size: PAGE_SIZE }).subscribe({
      next: result => {
        if (snapshot !== this.applied) { return; }
        this.loading = false;
        if (pageNo === 0) {
          this.entries = result.content;
          this.expandedId = null;
          this.runSections = null;
        } else {
          const known = new Set(this.entries.map(e => e.id));
          this.entries = [...this.entries, ...result.content.filter(e => !known.has(e.id))];
        }
        this.totalElements = result.totalElements;
        this.currentPage = result.page;
      },
      error: () => {
        if (snapshot !== this.applied) { return; }
        this.loading = false;
        this.entriesError = 'Das Protokoll konnte nicht geladen werden.';
      }
    });
    if (pageNo === 0) {
      this.entriesSub = sub;
    }
  }

  private fromIso(): string {
    return toLocalIso(rangeStart(this.rangeKey, new Date()));
  }

  private buildQuery(): SystemLogQuery {
    const query: SystemLogQuery = { from: this.fromIso() };
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
