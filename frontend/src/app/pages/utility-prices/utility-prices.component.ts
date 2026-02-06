import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { UtilityPriceFormComponent } from '../../components/utility-price-form/utility-price-form.component';
import { UtilityPriceService } from '../../services/utility-price.service';
import { UtilityPrice } from '../../models/utility-price.model';
import { MeterType } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';

/**
 * Seiten-Komponente für die Verwaltung von Versorgerpreisen
 * Zeigt alle Preise gruppiert nach Zählertyp und ermöglicht das Erstellen und Löschen
 */
@Component({
  selector: 'app-utility-prices',
  standalone: true,
  imports: [CommonModule, IconComponent, UtilityPriceFormComponent],
  templateUrl: './utility-prices.component.html',
  styleUrl: './utility-prices.component.scss'
})
export class UtilityPricesComponent implements OnInit {
  private readonly utilityPriceService = inject(UtilityPriceService);

  /** Alle geladenen Preise */
  prices: UtilityPrice[] = [];

  /** Gruppierte Preise nach Zählertyp */
  groupedPrices: Map<MeterType, UtilityPrice[]> = new Map();

  /** Loading-Status */
  isLoading = false;

  /** Error-Message */
  errorMessage: string | null = null;

  /** Success-Message */
  successMessage: string | null = null;

  /** MeterTypeUtils für Template-Zugriff */
  meterTypeUtils = MeterTypeUtils;

  /** Alle Meter Types für die Gruppierung */
  readonly meterTypes = [MeterType.ELECTRICITY, MeterType.WATER];

  ngOnInit(): void {
    this.loadPrices();
  }

  /**
   * Lädt alle Preise vom Server
   */
  loadPrices(): void {
    this.isLoading = true;
    this.errorMessage = null;

    this.utilityPriceService.getAllPrices().subscribe({
      next: (prices) => {
        this.prices = prices;
        this.groupPricesByMeterType();
        this.isLoading = false;
      },
      error: (error: Error) => {
        this.errorMessage = error.message;
        this.isLoading = false;
      }
    });
  }

  /**
   * Gruppiert Preise nach Zählertyp und sortiert nach Datum
   */
  private groupPricesByMeterType(): void {
    this.groupedPrices.clear();

    // Initialisiere alle Typen mit leeren Arrays
    this.meterTypes.forEach(type => {
      this.groupedPrices.set(type, []);
    });

    // Gruppiere und sortiere
    this.prices.forEach(price => {
      const existingPrices = this.groupedPrices.get(price.meterType) || [];
      existingPrices.push(price);
      this.groupedPrices.set(price.meterType, existingPrices);
    });

    // Sortiere jede Gruppe nach validFrom (neueste zuerst)
    this.groupedPrices.forEach((prices, type) => {
      prices.sort((a, b) => b.validFrom.getTime() - a.validFrom.getTime());
    });
  }

  /**
   * Prüft, ob ein Preis aktuell gültig ist
   */
  isCurrentPrice(price: UtilityPrice): boolean {
    const now = new Date();
    const isAfterValidFrom = price.validFrom <= now;
    const isBeforeValidTo = !price.validTo || price.validTo >= now;
    return isAfterValidFrom && isBeforeValidTo;
  }

  /**
   * Formatiert ein Datum für die Anzeige
   */
  formatDate(date: Date | undefined): string {
    if (!date) {
      return '—';
    }
    return new Intl.DateTimeFormat('de-DE', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).format(date);
  }

  /**
   * Formatiert einen Preis für die Anzeige
   */
  formatPrice(price: number): string {
    return new Intl.NumberFormat('de-DE', {
      style: 'currency',
      currency: 'EUR',
      minimumFractionDigits: 4,
      maximumFractionDigits: 4
    }).format(price);
  }

  /**
   * Löscht einen Preis nach Bestätigung
   */
  deletePrice(price: UtilityPrice): void {
    if (!price.id) {
      return;
    }

    const meterTypeLabel = this.meterTypeUtils.getLabel(price.meterType);
    const priceFormatted = this.formatPrice(price.pricePerUnit);
    const confirmMessage = `Möchten Sie den Preis ${priceFormatted} für ${meterTypeLabel} wirklich löschen?`;

    if (!confirm(confirmMessage)) {
      return;
    }

    this.utilityPriceService.deletePrice(price.id).subscribe({
      next: () => {
        this.successMessage = 'Preis erfolgreich gelöscht';
        this.loadPrices();

        setTimeout(() => {
          this.successMessage = null;
        }, 3000);
      },
      error: (error: Error) => {
        this.errorMessage = error.message;

        setTimeout(() => {
          this.errorMessage = null;
        }, 5000);
      }
    });
  }

  /**
   * Event-Handler für erfolgreiches Erstellen eines Preises
   */
  onPriceCreated(): void {
    this.loadPrices();
  }

  /**
   * Gibt die Schlüssel der Map als Array zurück (für ngFor)
   */
  getMeterTypeKeys(): MeterType[] {
    return Array.from(this.groupedPrices.keys());
  }

  /**
   * Gibt die Preise für einen Zählertyp zurück
   */
  getPricesForType(type: MeterType): UtilityPrice[] {
    return this.groupedPrices.get(type) || [];
  }
}
