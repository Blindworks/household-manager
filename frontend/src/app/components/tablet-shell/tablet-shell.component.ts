import { Component, Input, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Subscription, interval, startWith } from 'rxjs';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { weatherSymbol, weatherMaterialSymbol } from '../../shared/weather-icon.util';
import { TABLET_VIEWS } from '../../shared/tablet-views';

/**
 * Rahmen der Tablet-Unteransichten: oben dieselbe Uhr-/Wetterzeile wie im
 * Dashboard, unten dieselbe Ansichtsleiste, dazwischen der Seiteninhalt.
 *
 * Die `lumina`-Styles des Dashboards sind in dessen SCSS gekapselt und greifen
 * hier nicht - diese Komponente bringt die Kopf- und Leistenstile deshalb
 * selbst mit. Geteilt sind die *Daten* (`TABLET_VIEWS`, `weatherMaterialSymbol`),
 * nicht das CSS.
 */
@Component({
  selector: 'app-tablet-shell',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive],
  templateUrl: './tablet-shell.component.html',
  styleUrl: './tablet-shell.component.scss'
})
export class TabletShellComponent implements OnInit, OnDestroy {
  /** Ueberschrift der Seite, rechts neben der Uhr-/Wetterzeile. */
  @Input() heading = '';

  private readonly weatherService = inject(WeatherService);
  private clockSubscription?: Subscription;
  private weatherSubscription?: Subscription;

  readonly views = TABLET_VIEWS;

  private now = new Date();
  private weather: WeatherOverview | null = null;
  private weatherError = false;

  ngOnInit(): void {
    this.clockSubscription = interval(1000)
      .pipe(startWith(0))
      .subscribe(() => (this.now = new Date()));
    this.weatherSubscription = this.weatherService.getOverview().subscribe({
      next: overview => {
        this.weather = overview;
        this.weatherError = false;
      },
      error: () => (this.weatherError = true)
    });
  }

  ngOnDestroy(): void {
    this.clockSubscription?.unsubscribe();
    this.weatherSubscription?.unsubscribe();
  }

  /** Uhrzeit im Format HH:MM (24h). */
  get clockTime(): string {
    return this.now.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' });
  }

  /** Wochentag ausgeschrieben, z. B. "Montag". */
  get weekday(): string {
    return this.now.toLocaleDateString('de-DE', { weekday: 'long' });
  }

  /** Datum, z. B. "21. August". */
  get calendarDate(): string {
    return this.now.toLocaleDateString('de-DE', { day: 'numeric', month: 'long' });
  }

  /** Aktuelle Aussentemperatur, gerundet auf Ganzzahl. */
  get temperature(): string {
    const value = this.weather?.current?.temperature;
    return value == null ? '--' : `${Math.round(value)}`;
  }

  get weatherLabel(): string {
    return this.weatherError ? 'Nicht verfügbar' : weatherSymbol(this.weather?.current?.icon).label;
  }

  get weatherIcon(): string {
    return weatherMaterialSymbol(this.weather?.current?.icon);
  }
}
