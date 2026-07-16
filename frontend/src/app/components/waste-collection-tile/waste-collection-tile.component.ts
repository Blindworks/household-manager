import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription, interval, merge, of, startWith, switchMap, timer } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { WasteCollectionService } from '../../services/waste-collection.service';
import { WasteCollectionEvent } from '../../models/waste-collection.model';

/**
 * Dashboard-Kachel mit den anstehenden Muellabfuhr-Terminen.
 * Rendert sich nur, wenn im konfigurierten Vorschau-Fenster etwas ansteht.
 */
@Component({
  selector: 'app-waste-collection-tile',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './waste-collection-tile.component.html',
  styleUrl: './waste-collection-tile.component.scss'
})
export class WasteCollectionTileComponent implements OnInit, OnDestroy {
  private readonly wasteService = inject(WasteCollectionService);

  /** Haelt die Termine ueber den Tag hinweg aktuell (z. B. bei geaenderten Einstellungen). */
  private static readonly REFRESH_MS = 3600000;
  private static readonly DAY_MS = 86400000;

  private subscription?: Subscription;

  events: WasteCollectionEvent[] = [];

  ngOnInit(): void {
    this.subscription = merge(
      interval(WasteCollectionTileComponent.REFRESH_MS),
      // Zusaetzlich kurz nach Mitternacht: daysUntil wird serverseitig zum Abrufzeitpunkt
      // berechnet, ein rein stuendlicher Takt liesse "Morgen" also bis zu eine Stunde
      // ueber den Tageswechsel hinaus stehen bleiben.
      timer(this.msUntilNextMidnight(), WasteCollectionTileComponent.DAY_MS)
    )
      .pipe(
        startWith(0),
        switchMap(() => this.wasteService.getUpcoming().pipe(catchError(() => of([]))))
      )
      .subscribe(events => this.events = events);
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
  }

  /**
   * Millisekunden bis kurz nach dem naechsten lokalen Mitternachtswechsel.
   * Der kleine Versatz (5s) ist bewusst: bei exakt 00:00:00 koennte der Server bei
   * minimal abweichender Uhr noch "gestern" liefern.
   */
  private msUntilNextMidnight(): number {
    const now = new Date();
    const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 5);
    return midnight.getTime() - now.getTime();
  }

  /** "Heute"/"Morgen"/"Übermorgen", darueber hinaus der Wochentag. */
  relativeDayLabel(event: WasteCollectionEvent): string {
    switch (event.daysUntil) {
      case 0: return 'Heute';
      case 1: return 'Morgen';
      case 2: return 'Übermorgen';
      default: return this.weekdayOf(event.date);
    }
  }

  /**
   * Wochentag zu einem ISO-Datum. Bewusst aus den Datumsteilen gebaut statt via
   * `new Date('2026-07-20')`: Diese Kurzform parst als UTC-Mitternacht und wuerde bei
   * negativem UTC-Offset den Vortag anzeigen.
   */
  private weekdayOf(isoDate: string): string {
    const [year, month, day] = isoDate.split('-').map(Number);
    return new Date(year, month - 1, day)
      .toLocaleDateString('de-DE', { weekday: 'long' });
  }

  isTomorrow(event: WasteCollectionEvent): boolean {
    return event.daysUntil === 1;
  }

  trackByEvent(_index: number, event: WasteCollectionEvent): string {
    return `${event.date}|${event.label}`;
  }
}
