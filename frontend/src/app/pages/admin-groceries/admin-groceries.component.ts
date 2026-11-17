import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { GroceryService } from '../../services/grocery.service';
import {
  GroceryChainStatus, GroceryMarket, GrocerySourceStatus
} from '../../models/grocery.model';
import { GroceryRunTracker } from '../../services/grocery-run-tracker.service';

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

interface Message {
  text: string;
  type: 'success' | 'error';
}

/** Admin-Seite der Supermarkt-Angebote: Standort, Schwelle, Märkte, Ketten, Quellenstatus. */
@Component({
  selector: 'app-admin-groceries',
  standalone: true,
  imports: [FormsModule, DatePipe],
  providers: [GroceryRunTracker],
  templateUrl: './admin-groceries.component.html',
  styleUrl: './admin-groceries.component.scss'
})
export class AdminGroceriesComponent implements OnInit {
  private readonly api = inject(GroceryService);
  private readonly runTracker = inject(GroceryRunTracker);

  readonly thresholdMin = THRESHOLD_MIN;
  readonly thresholdMax = THRESHOLD_MAX;

  readonly markets = signal<GroceryMarket[]>([]);
  readonly chains = signal<GroceryChainStatus[]>([]);
  readonly sources = signal<GrocerySourceStatus[]>([]);
  readonly failedChains = signal<string[]>([]);
  readonly errorMessage = signal<string | null>(null);
  readonly settingsMessage = signal<Message | null>(null);
  readonly runMessage = this.runTracker.message;
  readonly refreshingMarkets = signal(false);
  readonly running = this.runTracker.running;
  readonly savingSettings = signal(false);

  zip = '';
  threshold: number | null = null;
  private lat: number | null = null;
  private lng: number | null = null;

  constructor() {
    this.runTracker.finished.pipe(takeUntilDestroyed()).subscribe(() => this.loadSources());
  }

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
    this.runTracker.init();
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

  runNow(): void {
    this.runTracker.start();
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
