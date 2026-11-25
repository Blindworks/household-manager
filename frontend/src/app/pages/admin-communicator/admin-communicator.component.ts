import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import {
  CommunicatorHealth, CommunicatorLogSummary, CommunicatorRequestDetail, CommunicatorRequestRow,
  CommunicatorRequestStatus
} from '../../models/communicator-log.model';
import { CommunicatorLogQuery, CommunicatorLogService } from '../../services/communicator-log.service';
import {
  cacheShare, cardLines, formatCount, formatDecimal, formatMs, requestStatusLabel, requestTone, rowTokens, totalTokens, whisperState
} from '../../shared/communicator-log-view.util';

/** Einblick in den Communicator: Zustand, Kennzahlen und der Verlauf jeder Anfrage. Nur lesend. */
@Component({
  selector: 'app-admin-communicator',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './admin-communicator.component.html',
  styleUrl: './admin-communicator.component.scss'
})
export class AdminCommunicatorComponent implements OnInit, OnDestroy {
  private readonly service = inject(CommunicatorLogService);

  readonly ranges = [{ days: 1, label: 'Heute' }, { days: 7, label: '7 Tage' }, { days: 30, label: '30 Tage' }];
  readonly formatMs = formatMs;
  readonly formatCount = formatCount;
  readonly formatDecimal = formatDecimal;
  readonly totalTokens = totalTokens;
  readonly cacheShare = cacheShare;
  readonly rowTokens = rowTokens;
  readonly statusLabel = requestStatusLabel;
  readonly toneOf = requestTone;
  readonly cardLines = cardLines;
  readonly whisperState = whisperState;

  days = 7;
  problemsOnly = false;
  search = '';

  health: CommunicatorHealth | null = null;
  healthLoading = false;
  healthError: string | null = null;
  summary: CommunicatorLogSummary | null = null;
  summaryError: string | null = null;
  rows: CommunicatorRequestRow[] = [];
  total = 0;
  rowsLoading = false;
  rowsError: string | null = null;

  expandedId: number | null = null;
  detail: CommunicatorRequestDetail | null = null;
  detailError: string | null = null;

  /** Angewendeter Filter: „Mehr laden“ nutzt ihn, nie den Formularstand. */
  private applied: CommunicatorLogQuery = { days: 7, problemsOnly: false, q: '' };
  private page = 0;
  private listSub?: Subscription;
  private summarySub?: Subscription;

  ngOnInit(): void {
    this.reload();
  }

  ngOnDestroy(): void {
    this.listSub?.unsubscribe();
    this.summarySub?.unsubscribe();
  }

  reload(): void {
    this.loadHealth();
    this.apply();
  }

  setDays(days: number): void {
    this.days = days;
    this.apply();
  }

  apply(): void {
    this.applied = { days: this.days, problemsOnly: this.problemsOnly, q: this.search.trim() };
    this.loadSummary();
    this.loadRows(0);
  }

  loadMore(): void {
    this.loadRows(this.page + 1);
  }

  toggle(row: CommunicatorRequestRow): void {
    this.detail = null;
    this.detailError = null;
    this.expandedId = this.expandedId === row.id ? null : row.id;
    if (this.expandedId === null) {
      return;
    }
    this.service.getRequest(row.id).subscribe({
      next: detail => {
        if (this.expandedId === row.id) {
          this.detail = detail;
        }
      },
      error: () => {
        if (this.expandedId === row.id) {
          this.detailError = 'Der Verlauf konnte nicht geladen werden.';
        }
      }
    });
  }

  statusEntries(): { status: CommunicatorRequestStatus; count: number }[] {
    return Object.entries(this.summary?.byStatus ?? {})
      .map(([status, count]) => ({ status: status as CommunicatorRequestStatus, count: count ?? 0 }));
  }

  /** Balkenhöhe in Prozent des stärksten Tages. */
  barHeight(tokens: number): number {
    const max = Math.max(1, ...(this.summary?.days ?? []).map(day => totalTokens(day.tokens)));
    return (tokens / max) * 100;
  }

  perRequest(): number {
    const summary = this.summary;
    return summary && summary.requests > 0 ? Math.round(totalTokens(summary.tokens) / summary.requests) : 0;
  }

  private loadHealth(): void {
    this.healthLoading = true;
    this.service.getHealth().subscribe({
      next: health => {
        this.health = health;
        this.healthError = null;
        this.healthLoading = false;
      },
      error: () => {
        this.healthError = 'Der Zustand konnte nicht geladen werden.';
        this.healthLoading = false;
      }
    });
  }

  private loadSummary(): void {
    this.summarySub?.unsubscribe();
    this.summarySub = this.service.getSummary(this.applied.days).subscribe({
      next: summary => {
        this.summary = summary;
        this.summaryError = null;
      },
      error: () => (this.summaryError = 'Die Auswertung konnte nicht geladen werden.')
    });
  }

  private loadRows(page: number): void {
    this.listSub?.unsubscribe();
    this.rowsLoading = true;
    this.listSub = this.service.getRequests(this.applied, page).subscribe({
      next: result => {
        this.rowsLoading = false;
        this.rowsError = null;
        this.page = result.page;
        this.total = result.totalElements;
        if (page === 0) {
          this.rows = result.content;
          this.expandedId = null;
          this.detail = null;
        } else {
          const known = new Set(this.rows.map(row => row.id));
          this.rows = [...this.rows, ...result.content.filter(row => !known.has(row.id))];
        }
      },
      error: () => {
        this.rowsLoading = false;
        this.rowsError = 'Die Anfragen konnten nicht geladen werden.';
      }
    });
  }
}
