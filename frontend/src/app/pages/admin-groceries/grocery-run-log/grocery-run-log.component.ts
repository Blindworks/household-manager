import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { GroceryService } from '../../../services/grocery.service';
import { GroceryRunTracker } from '../../../services/grocery-run-tracker.service';
import {
  GroceryRunJudgementLine, GroceryRunLogDetail, GroceryRunLogSummary, GroceryRunProductLine, GroceryVerdict
} from '../../../models/grocery.model';
import {
  VERDICT_LABELS, VerdictFilter, countByVerdict, filterJudgements, formatRunDuration, formatTokens,
  judgementOfferMeta, productNormalPrice, productNote, reasonKindLabel, runStatusView, triggerLabel
} from '../../../shared/grocery-run-log.util';

const SOURCE_NAMES: Record<string, string> = {
  MARKTGURU: 'Marktguru', KAUFDA: 'Kaufda', REWE: 'REWE', PENNY: 'Penny',
  KAUFLAND: 'Kaufland', NORMA: 'Norma', LIDL: 'Lidl', LOGO: 'logo Getränke'
};

/**
 * Laufprotokoll der Supermarkt-Angebote auf der Admin-Seite: Liste der Läufe und je Lauf Quellen, Produkte und
 * die in diesem Lauf getroffenen Claude-Urteile. Nutzt den GroceryRunTracker der Elternseite, um nach einem
 * „Jetzt suchen“ neu zu laden.
 */
@Component({
  selector: 'app-grocery-run-log',
  standalone: true,
  imports: [DatePipe, FormsModule],
  templateUrl: './grocery-run-log.component.html',
  styleUrl: './grocery-run-log.component.scss'
})
export class GroceryRunLogComponent implements OnInit {
  private readonly api = inject(GroceryService);
  private readonly runTracker = inject(GroceryRunTracker);

  readonly runs = signal<GroceryRunLogSummary[]>([]);
  readonly loading = signal(true);
  readonly listError = signal<string | null>(null);
  readonly selectedId = signal<number | null>(null);
  readonly detail = signal<GroceryRunLogDetail | null>(null);
  readonly detailLoading = signal(false);
  readonly detailError = signal<string | null>(null);
  readonly verdictFilter = signal<VerdictFilter>('ALL');
  readonly productFilter = signal<number | null>(null);

  readonly verdictOptions: { value: VerdictFilter; label: string }[] = [
    { value: 'ALL', label: 'Alle' },
    { value: 'MATCH', label: VERDICT_LABELS.MATCH },
    { value: 'MAYBE', label: VERDICT_LABELS.MAYBE },
    { value: 'NO', label: VERDICT_LABELS.NO }
  ];

  readonly selectedRun = computed(() => this.runs().find(r => r.id === this.selectedId()) ?? this.detail()?.run ?? null);
  readonly verdictCounts = computed(() => countByVerdict(this.detail()?.judgements ?? []));
  readonly visibleJudgements = computed(() =>
    filterJudgements(this.detail()?.judgements ?? [], this.verdictFilter(), this.productFilter()));
  /** Produkte, zu denen es in diesem Lauf Urteile gab (für die Auswahl). */
  readonly judgedProducts = computed(() => {
    const seen = new Map<number, string>();
    (this.detail()?.judgements ?? []).forEach(j => seen.set(j.productId, j.productName));
    return [...seen].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name, 'de'));
  });

  private detailRequest: Subscription | null = null;

  constructor() {
    this.runTracker.finished.pipe(takeUntilDestroyed()).subscribe(() => this.loadRuns());
  }

  ngOnInit(): void {
    this.loadRuns();
  }

  status = runStatusView;
  trigger = triggerLabel;
  duration = (run: GroceryRunLogSummary) => formatRunDuration(run.startedAt, run.finishedAt);
  tokens = formatTokens;
  offerMeta = judgementOfferMeta;
  normalPrice = productNormalPrice;
  note = productNote;
  reasonKind = reasonKindLabel;

  verdictLabel(verdict: GroceryVerdict): string {
    return VERDICT_LABELS[verdict];
  }

  sourceName(source: string): string {
    return SOURCE_NAMES[source] ?? source;
  }

  verdictCount(filter: VerdictFilter): number {
    const counts = this.verdictCounts();
    return filter === 'ALL' ? counts.MATCH + counts.MAYBE + counts.NO : counts[filter];
  }

  /** Antippen eines Laufs klappt ihn auf; erneutes Antippen schließt ihn. */
  toggle(run: GroceryRunLogSummary): void {
    if (this.selectedId() === run.id) {
      this.close();
      return;
    }
    this.selectedId.set(run.id);
    this.verdictFilter.set('ALL');
    this.productFilter.set(null);
    this.loadDetail(run.id);
  }

  close(): void {
    this.detailRequest?.unsubscribe();
    this.selectedId.set(null);
    this.detail.set(null);
    this.detailError.set(null);
    this.detailLoading.set(false);
  }

  /** Klick auf eine Produktzeile zeigt nur dessen Urteile. */
  showProduct(product: GroceryRunProductLine): void {
    this.productFilter.set(this.productFilter() === product.productId ? null : product.productId);
  }

  setProductFilter(value: string): void {
    this.productFilter.set(value === '' ? null : Number(value));
  }

  trackJudgement(index: number, j: GroceryRunJudgementLine): string {
    return `${j.productId}-${index}`;
  }

  private loadRuns(): void {
    this.api.getRunLog().subscribe({
      next: runs => {
        this.runs.set(runs);
        this.loading.set(false);
        this.listError.set(null);
      },
      error: error => {
        this.loading.set(false);
        this.listError.set(error.message);
      }
    });
  }

  private loadDetail(id: number): void {
    this.detailRequest?.unsubscribe();
    this.detail.set(null);
    this.detailError.set(null);
    this.detailLoading.set(true);
    this.detailRequest = this.api.getRunLogDetail(id).subscribe({
      next: detail => {
        if (this.selectedId() !== id) {
          return;
        }
        this.detail.set(detail);
        this.detailLoading.set(false);
      },
      error: error => {
        if (this.selectedId() !== id) {
          return;
        }
        this.detailLoading.set(false);
        this.detailError.set(error.message);
      }
    });
  }
}
