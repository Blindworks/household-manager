import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { PetFoodService } from '../../services/pet-food.service';
import { TractiveService } from '../../services/tractive.service';
import { PetFoodStatus } from '../../models/pet-food.model';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';
import { PetFoodTone, petFoodBarWidth, petFoodTone } from '../../shared/pet-food-level.util';

/**
 * Hundeuebersicht fuer das Wandtablet: Futtervorrat, Spaziergaenge,
 * Tracker-Status und Position in einem 2x2-Raster, alles gleichzeitig sichtbar
 * und ohne Scrollen.
 *
 * Rein anzeigend. Buchungen bleiben der Seite /pet-food und dem
 * Dashboard-Dialog vorbehalten - auf dem Tablet laeuft die KIOSK-Rolle, dort
 * wuerde jede Buchung ohnehin mit 403 scheitern.
 */
/** Auswaehlbare Zeitraeume der Spaziergangs-Kachel. 30 ist die Backend-Obergrenze. */
export type WalkRangeDays = 7 | 14 | 30;

@Component({
  selector: 'app-tablet-toni',
  standalone: true,
  imports: [CommonModule, TabletShellComponent],
  templateUrl: './tablet-toni.component.html',
  styleUrl: './tablet-toni.component.scss'
})
export class TabletToniComponent implements OnInit, OnDestroy {
  /** Das Tablet haengt dauerhaft in dieser Ansicht und muss sich selbst aktualisieren. */
  private static readonly REFRESH_INTERVAL_MS = 5 * 60 * 1000;

  private readonly petFoodService = inject(PetFoodService);
  private readonly tractiveService = inject(TractiveService);
  private refreshTimer: number | null = null;

  readonly walkRanges: readonly WalkRangeDays[] = [7, 14, 30];

  food: PetFoodStatus | null = null;
  foodError: string | null = null;
  pet: TractivePet | null = null;
  petError: string | null = null;
  walks: TractiveWalk[] = [];
  walkError: string | null = null;
  walkDays: WalkRangeDays = 7;

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(
      () => this.reload(),
      TabletToniComponent.REFRESH_INTERVAL_MS
    );
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
      this.refreshTimer = null;
    }
  }

  /** Turnusmaessige Aktualisierung: ein Fehlschlag laesst die Anzeige stehen. */
  reload(): void {
    this.load(true);
  }

  /** Ton des Fuellstands; ohne Daten neutral. Regel in shared/pet-food-level.util.ts. */
  get foodTone(): PetFoodTone {
    return this.food ? petFoodTone(this.food) : 'ok';
  }

  get foodBarWidth(): number {
    return this.food ? petFoodBarWidth(this.food.percent) : 0;
  }

  setWalkDays(days: WalkRangeDays): void {
    if (days === this.walkDays) {
      return;
    }
    this.walkDays = days;
    this.loadWalks(false);
  }

  /**
   * Die drei Quellen laufen unabhaengig: faellt Tractive aus, steht der
   * Futtervorrat trotzdem noch da - und umgekehrt.
   */
  private load(silent: boolean): void {
    this.loadFood(silent);
    this.loadPet(silent);
  }

  private loadFood(silent: boolean): void {
    this.petFoodService.getStatus().subscribe({
      next: food => {
        this.food = food;
        this.foodError = null;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden des Futtervorrats:', error);
        // Ein misslungener Hintergrundabruf darf die zuletzt bekannten Werte nicht
        // durch eine Fehlermeldung ersetzen - alte Zahlen sind auf einer Wandanzeige
        // mehr wert als gar keine.
        if (!silent) {
          this.foodError = 'Futtervorrat nicht verfügbar.';
        }
      }
    });
  }

  private loadPet(silent: boolean): void {
    this.tractiveService.getPets().subscribe({
      next: pets => {
        // Bewusst das erste Tier: bei genau einem Hund ist das Toni. Mehrere Tiere
        // waeren eine eigene Entscheidung, keine stille Erweiterung des Rasters.
        this.pet = pets[0] ?? null;
        this.petError = null;
        this.loadWalks(silent);
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden des Trackers:', error);
        if (!silent) {
          this.petError = 'Tracker nicht verfügbar.';
        }
      }
    });
  }

  // Bewusst ohne switchMap/Request-Guard: ein langsamer Abruf koennte theoretisch
  // nach einem schnelleren eintreffen und veraltete Runden schreiben. Auf einer
  // Wandanzeige mit seltener Interaktion und 5-Minuten-Refresh ist die Folge
  // harmlos und selbstheilend.
  private loadWalks(silent: boolean): void {
    const trackerId = this.pet?.trackerId;
    if (!trackerId) {
      return;
    }
    this.tractiveService.getWalks(trackerId, this.walkDays).subscribe({
      next: walks => {
        this.walks = walks;
        this.walkError = null;
      },
      error: (error: Error) => {
        console.error('Fehler beim Laden der Spaziergänge:', error);
        if (!silent) {
          // Der haeufigste Grund ist das Rate-Limit der Tractive-Cloud.
          this.walkError = 'Spaziergänge nicht verfügbar.';
        }
      }
    });
  }
}
