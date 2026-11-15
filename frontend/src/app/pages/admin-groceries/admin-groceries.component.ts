import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Subscription, catchError, of, switchMap, take, timer } from 'rxjs';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { GroceryService } from '../../services/grocery.service';
import {
  GroceryChainStatus, GroceryMarket, GrocerySourceStatus
} from '../../models/grocery.model';

/** Kettenreihenfolge und Anzeigename der Ketten mit Marktauswahl (Chain.hasMarkets im Backend). */
const MARKET_CHAINS: { chain: string; name: string }[] = [
  { chain: 'REWE', name: 'REWE' },
  { chain: 'PENNY', name: 'Penny' },
  { chain: 'KAUFLAND', name: 'Kaufland' },
  { chain: 'EDEKA', name: 'Edeka' }
];
const CHAIN_NAMES = new Map(MARKET_CHAINS.map(c => [c.chain, c.name]));

const SOURCE_NAMES: Record<string, string> = {
  MARKTGURU: 'Marktguru', KAUFDA: 'Kaufda', REWE: 'REWE', PENNY: 'Penny',
  KAUFLAND: 'Kaufland', NORMA: 'Norma', LIDL: 'Lidl'
};

/** Schwelle für „Bestpreis“-Hinweise; verbindlich im Backend (GrocerySettingsService), hier nur Bedienhilfe. */
export const THRESHOLD_MIN = 1;
export const THRESHOLD_MAX = 50;

/** „Jetzt suchen“ läuft im Hintergrund: Quellenstatus alle 10 s, höchstens 15 Minuten lang. */
const RUN_POLL_MS = 10_000;
const RUN_MAX_POLLS = 90;

interface Message {
  text: string;
  type: 'success' | 'error';
}

/** Admin-Seite der Supermarkt-Angebote: Standort, Schwelle, Märkte, Ketten, Quellenstatus. */
@Component({
  selector: 'app-admin-groceries',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './admin-groceries.component.html',
  styleUrl: './admin-groceries.component.scss'
})
export class AdminGroceriesComponent implements OnInit, OnDestroy {
  private readonly api = inject(GroceryService);

  readonly thresholdMin = THRESHOLD_MIN;
  readonly thresholdMax = THRESHOLD_MAX;

  readonly markets = signal<GroceryMarket[]>([]);
  readonly chains = signal<GroceryChainStatus[]>([]);
  readonly sources = signal<GrocerySourceStatus[]>([]);
  readonly failedChains = signal<string[]>([]);
  readonly errorMessage = signal<string | null>(null);
  readonly settingsMessage = signal<Message | null>(null);
  readonly runMessage = signal<Message | null>(null);
  readonly refreshingMarkets = signal(false);
  readonly running = signal(false);
  readonly savingSettings = signal(false);

  zip = '';
  threshold: number | null = null;
  private lat: number | null = null;
  private lng: number | null = null;
  private runPoll: Subscription | null = null;

  readonly marketGroups = computed(() => MARKET_CHAINS.map(({ chain, name }) => ({
    chain, name, markets: this.markets().filter(m => m.chain === chain)
  })));

  ngOnInit(): void {
    this.api.getSettings().subscribe({
      next: settings => {
        this.zip = settings.zip ?? '';
        this.lat = settings.lat;
        this.lng = settings.lng;
        this.threshold = settings.dealThresholdPercent;
      },
      error: error => this.errorMessage.set(error.message)
    });
    this.loadMarkets();
    this.loadChains();
    this.loadSources();
  }

  get thresholdValid(): boolean {
    return this.threshold !== null && Number.isInteger(this.threshold)
      && this.threshold >= THRESHOLD_MIN && this.threshold <= THRESHOLD_MAX;
  }

  sourceName(source: string): string {
    return SOURCE_NAMES[source] ?? source;
  }

  failedChainText(chain: string): string {
    return `Für ${CHAIN_NAMES.get(chain) ?? chain} konnte keine Marktliste geladen werden.`;
  }

  saveSettings(): void {
    if (!this.thresholdValid || this.savingSettings()) {
      return;
    }
    this.savingSettings.set(true);
    this.api.updateSettings({
      zip: this.zip.trim() || null, lat: this.lat, lng: this.lng, dealThresholdPercent: this.threshold
    }).subscribe({
      next: saved => {
        this.lat = saved.lat;
        this.lng = saved.lng;
        this.savingSettings.set(false);
        this.settingsMessage.set({ text: 'Gespeichert.', type: 'success' });
      },
      error: error => {
        this.savingSettings.set(false);
        this.settingsMessage.set({ text: error.message, type: 'error' });
      }
    });
  }

  refreshMarkets(): void {
    if (this.refreshingMarkets()) {
      return;
    }
    this.refreshingMarkets.set(true);
    this.api.refreshMarkets().subscribe({
      next: result => {
        this.refreshingMarkets.set(false);
        this.markets.set(result.markets);
        this.failedChains.set(result.failedChains);
      },
      error: error => {
        this.refreshingMarkets.set(false);
        this.errorMessage.set(error.message);
      }
    });
  }

  toggleMarket(market: GroceryMarket, selected: boolean): void {
    this.api.setMarketSelected(market.id, selected).subscribe({
      next: updated => this.markets.update(list => list.map(m => (m.id === updated.id ? updated : m))),
      error: error => {
        this.errorMessage.set(error.message);
        this.loadMarkets();
      }
    });
  }

  toggleChain(chain: GroceryChainStatus, enabled: boolean): void {
    this.api.setChainEnabled(chain.chain, enabled).subscribe({
      next: () => this.chains.update(list => list.map(c => (c.chain === chain.chain ? { ...c, enabled } : c))),
      error: error => {
        this.errorMessage.set(error.message);
        this.loadChains();
      }
    });
  }

  ngOnDestroy(): void {
    this.runPoll?.unsubscribe();
  }

  /**
   * Startet den Lauf (202) und fragt den Quellenstatus ab, bis eine Quelle einen jüngeren Lauf meldet als beim
   * Klick bekannt. Verglichen wird mit dem Serverstand statt der Browseruhr, damit Uhrabweichungen nicht stören.
   */
  runNow(): void {
    if (this.running()) {
      return;
    }
    this.running.set(true);
    this.runMessage.set(null);
    const before = latestRunAt(this.sources());
    this.api.runNow().subscribe({
      next: () => {
        this.runMessage.set({ text: 'Suche läuft …', type: 'success' });
        this.pollUntilRunFinished(before);
      },
      error: error => {
        this.running.set(false);
        this.runMessage.set({ text: error.message, type: 'error' });
      }
    });
  }

  private pollUntilRunFinished(before: string | null): void {
    this.runPoll?.unsubscribe();
    this.runPoll = timer(RUN_POLL_MS, RUN_POLL_MS).pipe(
      take(RUN_MAX_POLLS),
      switchMap(() => this.api.getSources().pipe(catchError(() => of(null))))
    ).subscribe({
      next: sources => {
        const fresh = (sources ?? []).filter(s => s.lastRunAt !== null && (before === null || s.lastRunAt > before));
        if (sources === null || fresh.length === 0) {
          return;
        }
        this.sources.set(sources);
        this.running.set(false);
        this.runMessage.set({ text: runSummary(fresh), type: 'success' });
        this.runPoll?.unsubscribe();
      },
      complete: () => {
        if (this.running()) {
          this.running.set(false);
          this.runMessage.set({ text: 'Die Suche läuft noch – den Stand später hier prüfen.', type: 'success' });
        }
      }
    });
  }

  private loadMarkets(): void {
    this.api.getMarkets().subscribe({
      next: markets => this.markets.set(markets),
      error: error => this.errorMessage.set(error.message)
    });
  }

  private loadChains(): void {
    this.api.getChains().subscribe({
      next: chains => this.chains.set(chains),
      error: error => this.errorMessage.set(error.message)
    });
  }

  private loadSources(): void {
    this.api.getSources().subscribe({
      next: sources => this.sources.set(sources),
      error: error => this.errorMessage.set(error.message)
    });
  }
}

function latestRunAt(sources: GrocerySourceStatus[]): string | null {
  return sources.reduce<string | null>(
    (latest, s) => (s.lastRunAt !== null && (latest === null || s.lastRunAt > latest) ? s.lastRunAt : latest), null);
}

function runSummary(fresh: GrocerySourceStatus[]): string {
  const offers = fresh.reduce((sum, s) => sum + (s.offerCount ?? 0), 0);
  const failed = fresh.filter(s => s.ok === false).length;
  const failures = failed === 0 ? '' : failed === 1 ? ', 1 Quelle gestört' : `, ${failed} Quellen gestört`;
  return `Suche fertig: ${offers} Angebote${failures}.`;
}
