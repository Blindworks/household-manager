import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { PetFoodService } from '../../services/pet-food.service';
import { TractiveService } from '../../services/tractive.service';
import { PetFoodStatus } from '../../models/pet-food.model';
import { TractivePet, TractiveWalk } from '../../models/tractive.model';

/**
 * Hundeuebersicht fuer das Wandtablet: Futtervorrat, Spaziergaenge,
 * Tracker-Status und Position in einem 2x2-Raster, alles gleichzeitig sichtbar
 * und ohne Scrollen.
 *
 * Rein anzeigend. Buchungen bleiben der Seite /pet-food und dem
 * Dashboard-Dialog vorbehalten - auf dem Tablet laeuft die KIOSK-Rolle, dort
 * wuerde jede Buchung ohnehin mit 403 scheitern.
 */
@Component({
  selector: 'app-tablet-toni',
  standalone: true,
  imports: [CommonModule, TabletShellComponent],
  templateUrl: './tablet-toni.component.html',
  styleUrl: './tablet-toni.component.scss'
})
export class TabletToniComponent implements OnInit, OnDestroy {
  private readonly petFoodService = inject(PetFoodService);
  private readonly tractiveService = inject(TractiveService);

  food: PetFoodStatus | null = null;
  pet: TractivePet | null = null;
  walks: TractiveWalk[] = [];

  ngOnInit(): void {
    this.petFoodService.getStatus().subscribe({ next: food => (this.food = food) });
    this.tractiveService.getPets().subscribe({ next: pets => (this.pet = pets[0] ?? null) });
  }

  ngOnDestroy(): void {
    // Der Selbst-Refresh kommt in Task 5 dazu.
  }
}
