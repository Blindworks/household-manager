import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IconComponent } from '../../shared/components/icon/icon.component';
import { UtilityPriceFormComponent } from '../../components/utility-price-form/utility-price-form.component';
import { UtilityPriceService } from '../../services/utility-price.service';
import { UtilityPrice } from '../../models/utility-price.model';
import { MeterType } from '../../models/meter-reading.model';
import { MeterTypeUtils } from '../../utils/meter-type.utils';
import { AuthService } from '../../services/auth.service';

/**
 * Seiten-Komponente für die Verwaltung von Versorgerpreisen
 * Zeigt alle Preise gruppiert nach Zählertyp und ermöglicht das Erstellen und Löschen
 */
@Component({
  selector: 'app-utility-prices',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, UtilityPriceFormComponent],
  templateUrl: './utility-prices.component.html',
  styleUrl: './utility-prices.component.scss'
})
export class UtilityPricesComponent implements OnInit {
  private readonly utilityPriceService = inject(UtilityPriceService);
  private readonly authService = inject(AuthService);
  readonly isAdmin = this.authService.isAdmin;

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
  readonly meterTypes = [MeterType.ELECTRICITY, MeterType.GAS, MeterType.WATER];

  /** Gas: kWh je m³ - nur für ADMIN geladen und sichtbar. */
  gasKwhPerM3: number | null = null;

  /** Speicher-Status des Gasfaktors */
  isSavingSettings = false;

  /**
   * Eigenes Meldungspaar für den Gasfaktor, damit ein Fehler dort nicht als
   * Fehlerbanner über der (davon unabhängigen) Preistabelle erscheint.
   */
  settingsErrorMessage: string | null = null;
  settingsSuccessMessage: string | null = null;

  /**
   * Zulässiger Bereich des Gasfaktors. Verbindlich ist die Backend-Prüfung -
   * das hier ist nur Nutzer-Komfort (frühe Rückmeldung, Knopf-Sperre), keine
   * Sicherheitsgrenze. Einzige Definition, damit Knopf-Sperre und min/max-
   * Attribute im Template nicht auseinanderlaufen.
   */
  readonly gasKwhPerM3Min = 5;
  readonly gasKwhPerM3Max = 15;

  ngOnInit(): void {
    this.loadPrices();
    if (this.isAdmin()) {
      this.loadSettings();
    }
  }

  /**
   * Lädt den Gas-Umrechnungsfaktor (nur für ADMIN aufrufbar - der Endpunkt ist ADMIN-only)
   */
  private loadSettings(): void {
    this.utilityPriceService.getSettings().subscribe({
      next: settings => (this.gasKwhPerM3 = settings.gasKwhPerM3),
      error: () => (this.settingsErrorMessage = 'Der Gasfaktor konnte nicht geladen werden.')
    });
  }

  /**
   * Prüft, ob der eingegebene Gasfaktor im zulässigen Bereich liegt.
   */
  isGasFactorValid(): boolean {
    return (
      this.gasKwhPerM3 !== null &&
      this.gasKwhPerM3 >= this.gasKwhPerM3Min &&
      this.gasKwhPerM3 <= this.gasKwhPerM3Max
    );
  }

  /**
   * Speichert den Gas-Umrechnungsfaktor
   */
  saveGasFactor(): void {
    if (!this.isGasFactorValid() || this.gasKwhPerM3 === null) {
      return;
    }

    this.isSavingSettings = true;
    this.settingsErrorMessage = null;
    this.settingsSuccessMessage = null;

    this.utilityPriceService.updateSettings({ gasKwhPerM3: this.gasKwhPerM3 }).subscribe({
      next: settings => {
        this.gasKwhPerM3 = settings.gasKwhPerM3;
        this.settingsSuccessMessage = 'Gasfaktor gespeichert.';
        this.isSavingSettings = false;

        setTimeout(() => {
          this.settingsSuccessMessage = null;
        }, 3000);
      },
      error: (error: Error) => {
        this.settingsErrorMessage = error.message || 'Der Gasfaktor konnte nicht gespeichert werden.';
        this.isSavingSettings = false;

        setTimeout(() => {
          this.settingsErrorMessage = null;
        }, 5000);
      }
    });
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
    const priceFormatted = this.formatPrice(price.price);
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
