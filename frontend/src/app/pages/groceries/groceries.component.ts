import { Component, OnInit, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NgxEchartsDirective, provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { EChartsCoreOption } from 'echarts/core';
import { GroceryService } from '../../services/grocery.service';
import { HouseholdUserService } from '../../services/household-user.service';
import { AuthService } from '../../services/auth.service';
import {
  GroceryBaseUnit, GroceryDeal, GroceryOwner, GroceryPriceObservation, GroceryProduct, GroceryProductRequest
} from '../../models/grocery.model';
import { HouseholdUser } from '../../models/household-user.model';
import {
  GROCERY_UNIT_OPTIONS, formatBasePrice, formatReference, formatSaving, formatValidity, parseWordList
} from '../../shared/grocery-format.util';

echarts.use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

interface OwnerOption {
  value: GroceryOwner;
  label: string;
}

/** Eingaben des Produktformulars (Listen als Kommatext). */
interface ProductForm {
  id: number | null;
  name: string;
  searchWords: string;
  mustWords: string;
  excludeWords: string;
  baseUnit: GroceryBaseUnit;
  normalPrice: number | null;
  active: boolean;
  household: boolean;
}

/** Aufgeklappte Detailansicht eines Produkts: alle Angebote und der Preisverlauf. */
interface ProductDetail {
  offers: GroceryDeal[];
  chartOptions: EChartsCoreOption | null;
  error: string | null;
}

/**
 * Seite "Angebote": echte Schnaeppchen fuer die beobachteten Produkte (oben) und die
 * Pflege der eigenen Produkte (unten). Welche Angebote ein Produkt treffen, entscheidet der Server.
 */
@Component({
  selector: 'app-groceries',
  standalone: true,
  imports: [CommonModule, FormsModule, NgxEchartsDirective],
  providers: [provideEchartsCore({ echarts })],
  templateUrl: './groceries.component.html',
  styleUrl: './groceries.component.scss'
})
export class GroceriesComponent implements OnInit {
  private readonly groceryService = inject(GroceryService);
  private readonly userService = inject(HouseholdUserService);
  private readonly authService = inject(AuthService);

  readonly unitOptions = GROCERY_UNIT_OPTIONS;

  users: HouseholdUser[] = [];
  selectedOwner: GroceryOwner = 'all';

  deals: GroceryDeal[] = [];
  collectedAt: string | null = null;
  products: GroceryProduct[] = [];

  loading = true;
  error: string | null = null;
  actionError: string | null = null;
  busy = false;

  form: ProductForm | null = null;
  formError: string | null = null;
  pendingDeleteId: number | null = null;

  expandedProductId: number | null = null;
  details: Record<number, ProductDetail> = {};

  private readonly ownUserId = computed(() => this.authService.currentUser()?.id ?? null);

  ngOnInit(): void {
    const own = this.ownUserId();
    this.selectedOwner = own === null ? 'all' : String(own);
    this.userService.list().subscribe({
      next: users => this.users = users.filter(user => user.enabled),
      error: () => this.users = []
    });
    this.reload();
  }

  get ownerOptions(): OwnerOption[] {
    const own = this.ownUserId();
    const options: OwnerOption[] = [];
    if (own !== null) {
      options.push({ value: String(own), label: 'Meine' });
    }
    this.users.filter(user => user.id !== own)
      .forEach(user => options.push({ value: String(user.id), label: user.displayName }));
    options.push({ value: 'household', label: 'Haushalt' }, { value: 'all', label: 'Alle' });
    return options;
  }

  onOwnerChange(owner: GroceryOwner): void {
    this.selectedOwner = owner;
    this.expandedProductId = null;
    this.details = {};
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.error = null;
    this.loadDeals();
    this.groceryService.getProducts(this.selectedOwner).subscribe({
      next: products => { this.products = products; this.loading = false; },
      error: (err: Error) => { this.error = err.message; this.loading = false; }
    });
  }

  private loadDeals(): void {
    this.groceryService.getDeals(this.selectedOwner).subscribe({
      next: view => {
        this.collectedAt = view.collectedAt;
        this.deals = this.sortMatchesFirst(view.deals);
      },
      error: (err: Error) => this.error = err.message
    });
  }

  /** Sichere Treffer vor "passt vielleicht"; die Reihenfolge des Servers bleibt je Gruppe erhalten. */
  private sortMatchesFirst(deals: GroceryDeal[]): GroceryDeal[] {
    return [...deals.filter(d => d.verdict !== 'MAYBE'), ...deals.filter(d => d.verdict === 'MAYBE')];
  }

  trackDeal = (_: number, deal: GroceryDeal): string => this.dealKey(deal);

  dealKey(deal: GroceryDeal): string {
    return `${deal.productId}|${deal.fingerprint}|${deal.chain}|${deal.marketName ?? ''}`;
  }

  isEditable(productId: number): boolean {
    return this.products.find(p => p.id === productId)?.editable ?? false;
  }

  isMaybe(deal: GroceryDeal): boolean {
    return deal.verdict === 'MAYBE';
  }

  reject(deal: GroceryDeal): void {
    this.sendVerdict(deal, false);
  }

  confirmFit(deal: GroceryDeal): void {
    this.sendVerdict(deal, true);
  }

  private sendVerdict(deal: GroceryDeal, fits: boolean): void {
    if (this.busy) {
      return;
    }
    this.busy = true;
    this.actionError = null;
    this.groceryService.sendVerdict(deal.productId, deal.fingerprint, fits).subscribe({
      next: () => { this.busy = false; this.loadDeals(); },
      error: (err: Error) => { this.busy = false; this.actionError = err.message; }
    });
  }

  basePriceText(value: number | null, unit: GroceryBaseUnit | null): string {
    return value === null || unit === null ? '' : formatBasePrice(value, unit);
  }

  referenceText(deal: GroceryDeal): string {
    return formatReference(deal);
  }

  savingText(deal: GroceryDeal): string {
    return formatSaving(deal.savingPercent);
  }

  validityText(deal: GroceryDeal): string {
    return formatValidity(deal.validFrom, deal.validTo);
  }

  // ---- Produktformular ----

  openCreate(): void {
    this.formError = null;
    this.form = {
      id: null, name: '', searchWords: '', mustWords: '', excludeWords: '',
      baseUnit: 'LITER', normalPrice: null, active: true, household: false
    };
  }

  openEdit(product: GroceryProduct): void {
    this.formError = null;
    this.form = {
      id: product.id,
      name: product.name,
      searchWords: product.searchWords.join(', '),
      mustWords: product.mustWords.join(', '),
      excludeWords: product.excludeWords.join(', '),
      baseUnit: product.baseUnit,
      normalPrice: product.normalPricePerUnit,
      active: product.active,
      household: product.ownerUserId === null
    };
  }

  cancelForm(): void {
    this.form = null;
    this.formError = null;
  }

  saveForm(): void {
    const form = this.form;
    if (!form || this.busy) {
      return;
    }
    if (!form.name.trim()) {
      this.formError = 'Bitte einen Namen eingeben.';
      return;
    }
    const creating = form.id === null;
    const searchWords = parseWordList(form.searchWords);
    const request: GroceryProductRequest = {
      name: form.name.trim(),
      // Leere Suchwoerter schlaegt der Server selbst vor (beim Anlegen auch die Einheit).
      searchWords: searchWords.length === 0 ? null : searchWords,
      baseUnit: form.baseUnit,
      mustWords: parseWordList(form.mustWords),
      excludeWords: parseWordList(form.excludeWords),
      normalPricePerUnit: form.normalPrice,
      active: form.active,
      household: creating ? form.household : null
    };
    this.busy = true;
    this.formError = null;
    const call = creating
      ? this.groceryService.createProduct(request)
      : this.groceryService.updateProduct(form.id as number, request);
    call.subscribe({
      next: () => { this.busy = false; this.form = null; this.reload(); },
      error: (err: Error) => { this.busy = false; this.formError = err.message; }
    });
  }

  askDelete(product: GroceryProduct): void {
    this.pendingDeleteId = product.id;
  }

  cancelDelete(): void {
    this.pendingDeleteId = null;
  }

  pendingDeleteName(): string {
    return this.products.find(p => p.id === this.pendingDeleteId)?.name ?? '';
  }

  confirmDelete(): void {
    const id = this.pendingDeleteId;
    if (id === null || this.busy) {
      return;
    }
    this.busy = true;
    this.groceryService.deleteProduct(id).subscribe({
      next: () => { this.busy = false; this.pendingDeleteId = null; this.reload(); },
      error: (err: Error) => { this.busy = false; this.pendingDeleteId = null; this.actionError = err.message; }
    });
  }

  // ---- Details je Produkt ----

  toggleDetails(product: GroceryProduct): void {
    if (this.expandedProductId === product.id) {
      this.expandedProductId = null;
      return;
    }
    this.expandedProductId = product.id;
    const detail: ProductDetail = { offers: [], chartOptions: null, error: null };
    this.details[product.id] = detail;
    this.groceryService.getProductOffers(product.id).subscribe({
      next: offers => detail.offers = offers,
      error: (err: Error) => detail.error = err.message
    });
    this.groceryService.getPriceHistory(product.id).subscribe({
      next: history => detail.chartOptions = this.buildChart(history, product.baseUnit),
      error: (err: Error) => detail.error = err.message
    });
  }

  private buildChart(history: GroceryPriceObservation[], unit: GroceryBaseUnit): EChartsCoreOption | null {
    if (history.length === 0) {
      return null;
    }
    const weeks = [...new Set(history.map(h => h.isoWeek))].sort();
    const chains = [...new Set(history.map(h => h.chainName))];
    return {
      tooltip: { trigger: 'axis' },
      legend: { data: chains },
      grid: { left: 48, right: 16, top: 32, bottom: 28 },
      xAxis: { type: 'category', data: weeks },
      yAxis: { type: 'value', axisLabel: { formatter: (v: number) => formatBasePrice(v, unit) } },
      series: chains.map(chain => ({
        name: chain,
        type: 'line',
        connectNulls: true,
        data: weeks.map(week =>
          history.find(h => h.chainName === chain && h.isoWeek === week)?.basePrice ?? null)
      }))
    };
  }

  ownerLabel(product: GroceryProduct): string {
    return product.ownerUserId === null ? 'Haushalt' : (product.ownerName ?? '');
  }
}
