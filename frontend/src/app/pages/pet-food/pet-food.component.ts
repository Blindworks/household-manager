import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { PetSupplyService } from '../../services/pet-supply.service';
import { PetSupply, PetSupplyTransaction } from '../../models/pet-supply.model';
import {
  PET_SUPPLY_CRITICAL_DAYS,
  PetSupplyTone,
  petSupplyBarWidth,
  petSupplyTone
} from '../../shared/pet-supply-level.util';

/** Formularzustand einer Vorratskarte. */
interface SupplyForm {
  purchaseAmount: number | null;
  purchaseNote: string;
  correctionAmount: number | null;
  correctionNote: string;
  targetAmount: number | null;
}

/**
 * Seite "Toni-Vorraete": eine Karte je Vorrat (Futter, VomiSan-Tabletten) mit
 * Fuellstand, Reichweite, Buchungen, Zielbestand und Journal.
 *
 * Die Seite kennt die Artikel NICHT namentlich - Einheit, Eingaberaster und
 * Tagesverbrauch kommen aus der API, ein dritter Vorrat braucht hier keine
 * Aenderung.
 */
@Component({
  selector: 'app-pet-food',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './pet-food.component.html',
  styleUrl: './pet-food.component.scss'
})
export class PetFoodComponent implements OnInit {
  private readonly petSupplyService = inject(PetSupplyService);

  supplies: PetSupply[] = [];
  transactions: Record<string, PetSupplyTransaction[]> = {};
  forms: Record<string, SupplyForm> = {};
  /** Fehler je Vorrat - ein Fehler beim Futter darf die Tablettenkarte nicht bemalen. */
  errors: Record<string, string | null> = {};
  saving: Record<string, boolean> = {};

  loading = true;
  error: string | null = null;

  /** Einzige Definition in shared/pet-supply-level.util.ts. */
  readonly criticalDays = PET_SUPPLY_CRITICAL_DAYS;

  ngOnInit(): void {
    this.load();
  }

  fillTone(supply: PetSupply): PetSupplyTone {
    return petSupplyTone(supply);
  }

  barWidth(supply: PetSupply): number {
    return petSupplyBarWidth(supply.percent);
  }

  typeLabel(type: PetSupplyTransaction['type']): string {
    switch (type) {
      case 'FEEDING': return 'Fütterung';
      case 'PURCHASE': return 'Einkauf';
      default: return 'Korrektur';
    }
  }

  submitPurchase(supply: PetSupply): void {
    const form = this.forms[supply.key];
    if (form?.purchaseAmount == null || form.purchaseAmount <= 0) {
      return;
    }
    this.mutate(supply,
      this.petSupplyService.recordPurchase(supply.key, form.purchaseAmount, form.purchaseNote),
      () => { form.purchaseAmount = null; form.purchaseNote = ''; });
  }

  submitCorrection(supply: PetSupply): void {
    const form = this.forms[supply.key];
    if (form?.correctionAmount == null || form.correctionAmount < 0) {
      return;
    }
    this.mutate(supply,
      this.petSupplyService.correctStock(supply.key, form.correctionAmount, form.correctionNote),
      () => { form.correctionNote = ''; });
  }

  submitTarget(supply: PetSupply): void {
    const form = this.forms[supply.key];
    if (form?.targetAmount == null || form.targetAmount <= 0) {
      return;
    }
    this.mutate(supply, this.petSupplyService.updateTarget(supply.key, form.targetAmount), () => {});
  }

  private mutate(supply: PetSupply, request: Observable<PetSupply>, onSuccess: () => void): void {
    this.saving[supply.key] = true;
    this.errors[supply.key] = null;
    request.subscribe({
      next: updated => {
        this.saving[supply.key] = false;
        this.replaceSupply(updated);
        onSuccess();
        this.loadTransactions(updated.key);
      },
      error: (err: Error) => {
        this.saving[supply.key] = false;
        this.errors[supply.key] = err.message;
      }
    });
  }

  /**
   * Nur den betroffenen Vorrat ersetzen statt die ganze Liste neu zu laden -
   * ein zweiter Abruf wuerde die Eingaben der anderen Karte ueberschreiben.
   */
  private replaceSupply(updated: PetSupply): void {
    this.supplies = this.supplies.map(supply => supply.key === updated.key ? updated : supply);
    const form = this.forms[updated.key];
    if (form) {
      form.targetAmount = updated.targetAmount;
      form.correctionAmount = updated.amountRemaining;
    }
  }

  private load(): void {
    this.loading = true;
    this.petSupplyService.getSupplies().subscribe({
      next: supplies => {
        this.loading = false;
        this.supplies = supplies;
        supplies.forEach(supply => {
          this.forms[supply.key] = {
            purchaseAmount: null,
            purchaseNote: '',
            correctionAmount: supply.amountRemaining,
            correctionNote: '',
            targetAmount: supply.targetAmount
          };
          this.errors[supply.key] = null;
          this.saving[supply.key] = false;
          this.loadTransactions(supply.key);
        });
      },
      error: (err: Error) => {
        this.loading = false;
        this.error = err.message;
      }
    });
  }

  private loadTransactions(key: string): void {
    this.petSupplyService.getTransactions(key).subscribe({
      next: transactions => (this.transactions[key] = transactions),
      error: () => { /* Journalfehler blockiert die Seite nicht */ }
    });
  }
}
