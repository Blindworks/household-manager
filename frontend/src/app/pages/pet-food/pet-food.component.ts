import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PetFoodService } from '../../services/pet-food.service';
import { PetFoodStatus, PetFoodTransaction } from '../../models/pet-food.model';
import {
  PET_FOOD_CRITICAL_CANS,
  PetFoodTone,
  petFoodBarWidth,
  petFoodTone
} from '../../shared/pet-food-level.util';

/**
 * Seite "Futtervorrat": Fuellstand des MjamMjam-Dosenlagers fuer Toni,
 * Zubuchen/Korrigieren/Zielbestand und das Buchungsjournal. Die Warnschwelle
 * 7 Dosen entspricht dem Telegram-Flow auf sensor.pet_food_toni_cans.
 */
@Component({
  selector: 'app-pet-food',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './pet-food.component.html',
  styleUrl: './pet-food.component.scss'
})
export class PetFoodComponent implements OnInit {
  private readonly petFoodService = inject(PetFoodService);

  status: PetFoodStatus | null = null;
  transactions: PetFoodTransaction[] = [];
  loading = true;
  error: string | null = null;

  purchaseCans: number | null = null;
  purchaseNote = '';
  correctionCans: number | null = null;
  correctionNote = '';
  targetCans: number | null = null;
  saving = false;

  /** Einzige Definition in shared/pet-food-level.util.ts. */
  readonly criticalCans = PET_FOOD_CRITICAL_CANS;

  ngOnInit(): void {
    this.load();
  }

  fillTone(status: PetFoodStatus): PetFoodTone {
    return petFoodTone(status);
  }

  barWidth(status: PetFoodStatus): number {
    return petFoodBarWidth(status.percent);
  }

  typeLabel(type: PetFoodTransaction['type']): string {
    switch (type) {
      case 'FEEDING': return 'Fütterung';
      case 'PURCHASE': return 'Einkauf';
      default: return 'Korrektur';
    }
  }

  submitPurchase(): void {
    if (this.purchaseCans == null || this.purchaseCans <= 0) {
      return;
    }
    this.mutate(this.petFoodService.recordPurchase(this.purchaseCans, this.purchaseNote),
      () => { this.purchaseCans = null; this.purchaseNote = ''; });
  }

  submitCorrection(): void {
    if (this.correctionCans == null || this.correctionCans < 0) {
      return;
    }
    this.mutate(this.petFoodService.correctStock(this.correctionCans, this.correctionNote),
      () => { this.correctionCans = null; this.correctionNote = ''; });
  }

  submitTarget(): void {
    if (this.targetCans == null || this.targetCans <= 0) {
      return;
    }
    this.mutate(this.petFoodService.updateTarget(this.targetCans), () => {});
  }

  private mutate(request: ReturnType<PetFoodService['updateTarget']>, onSuccess: () => void): void {
    this.saving = true;
    this.error = null;
    request.subscribe({
      next: status => {
        this.saving = false;
        this.status = status;
        this.targetCans = status.targetCans;
        onSuccess();
        this.loadTransactions();
      },
      error: (err: Error) => {
        this.saving = false;
        this.error = err.message;
      }
    });
  }

  private load(): void {
    this.loading = true;
    this.petFoodService.getStatus().subscribe({
      next: status => {
        this.loading = false;
        this.status = status;
        this.targetCans = status.targetCans;
      },
      error: (err: Error) => {
        this.loading = false;
        this.error = err.message;
      }
    });
    this.loadTransactions();
  }

  private loadTransactions(): void {
    this.petFoodService.getTransactions().subscribe({
      next: transactions => (this.transactions = transactions),
      error: () => { /* Journalfehler blockiert die Seite nicht */ }
    });
  }
}
