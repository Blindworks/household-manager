import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { AuditLogService } from '../../services/audit-log.service';
import { AuditActionOption, AuditActorKind, AuditEntry, AuditQuery } from '../../models/auth.model';
import { RANGE_OPTIONS, RangeOption, rangeStart, toLocalIso } from '../../shared/system-log-view.util';

const PAGE_SIZE = 50;

export const AUDIT_ACTOR_KINDS: { kind: AuditActorKind; label: string }[] = [
  { kind: 'PERSON', label: 'Personen' },
  { kind: 'FLOW', label: 'Flows' },
  { kind: 'AGENT', label: 'Hausverstand' },
  { kind: 'TELEGRAM', label: 'Telegram' },
  { kind: 'SERVICE', label: 'Dienste' },
  { kind: 'SYSTEM', label: 'System' }
];

type AuditRangeKey = RangeOption['key'] | 'ALL';

@Component({
  selector: 'app-admin-audit-log',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-audit-log.component.html',
  styleUrl: './admin-audit-log.component.scss'
})
export class AdminAuditLogComponent implements OnInit, OnDestroy {
  private readonly auditLogService = inject(AuditLogService);

  readonly actorKinds = AUDIT_ACTOR_KINDS;
  readonly ranges: { key: AuditRangeKey; label: string }[] = [...RANGE_OPTIONS, { key: 'ALL', label: 'Alles' }];

  entries: AuditEntry[] = [];
  actions: AuditActionOption[] = [];
  totalElements = 0;
  currentPage = 0;
  loading = false;
  errorMessage: string | null = null;

  selectedKinds = new Set<AuditActorKind>(AUDIT_ACTOR_KINDS.map(option => option.kind));
  actionFilter = '';
  rangeKey: AuditRangeKey = 'ALL';
  searchText = '';

  /** Angewendeter Filterstand inkl. eingefrorenem from; „Mehr laden“ nutzt immer ihn, nie das Formular. */
  private applied: AuditQuery = {};
  private entriesSub?: Subscription;

  ngOnInit(): void {
    this.auditLogService.getActions().subscribe({
      next: actions => (this.actions = actions),
      error: () => (this.actions = [])
    });
    this.applyFilters();
  }

  ngOnDestroy(): void {
    this.entriesSub?.unsubscribe();
  }

  get hasMore(): boolean {
    return this.entries.length < this.totalElements;
  }

  applyFilters(): void {
    this.applied = this.buildQuery();
    this.loadPage(0);
  }

  toggleKind(kind: AuditActorKind): void {
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

  loadMore(): void {
    this.loadPage(this.currentPage + 1);
  }

  private loadPage(pageNo: number): void {
    if (pageNo === 0) {
      this.entriesSub?.unsubscribe();
    }
    const snapshot = this.applied;
    this.loading = true;
    this.errorMessage = null;
    const sub = this.auditLogService.search(snapshot, pageNo, PAGE_SIZE).subscribe({
      next: result => {
        if (snapshot !== this.applied) { return; }
        this.loading = false;
        if (pageNo === 0) {
          this.entries = result.content;
        } else {
          const known = new Set(this.entries.map(entry => entry.id));
          this.entries = [...this.entries, ...result.content.filter(entry => !known.has(entry.id))];
        }
        this.totalElements = result.totalElements;
        this.currentPage = result.page;
      },
      error: (e: Error) => {
        if (snapshot !== this.applied) { return; }
        this.loading = false;
        this.errorMessage = e.message;
      }
    });
    if (pageNo === 0) {
      this.entriesSub = sub;
    }
  }

  private buildQuery(): AuditQuery {
    const query: AuditQuery = {};
    if (this.rangeKey !== 'ALL') {
      query.from = toLocalIso(rangeStart(this.rangeKey, new Date()));
    }
    if (this.selectedKinds.size < AUDIT_ACTOR_KINDS.length) {
      query.actorKinds = AUDIT_ACTOR_KINDS.map(option => option.kind).filter(kind => this.selectedKinds.has(kind));
    }
    if (this.actionFilter) { query.action = this.actionFilter; }
    if (this.searchText.trim()) { query.q = this.searchText.trim(); }
    return query;
  }
}
