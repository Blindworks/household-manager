import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview, WeatherWarning } from '../../models/weather.model';
import { weatherSymbol, WeatherSymbol } from '../../shared/weather-icon.util';
import { IconComponent } from '../../shared/components/icon/icon.component';

/** Kompaktes Wetter-Widget fürs Dashboard, verlinkt auf /weather. */
@Component({
  selector: 'app-weather-widget',
  standalone: true,
  imports: [CommonModule, RouterLink, IconComponent],
  templateUrl: './weather-widget.component.html',
  styleUrl: './weather-widget.component.scss'
})
export class WeatherWidgetComponent implements OnInit {
  private readonly weatherService = inject(WeatherService);

  overview: WeatherOverview | null = null;
  hasError = false;

  ngOnInit(): void {
    this.weatherService.getOverview().subscribe({
      next: overview => (this.overview = overview),
      error: () => (this.hasError = true)
    });
  }

  symbolFor(icon: number | null | undefined): WeatherSymbol {
    return weatherSymbol(icon);
  }

  /** Kurzform der Warnung(en): erste Warnung als knapper Text, bei mehreren mit "+N". */
  warningLabel(): string {
    const warnings = this.overview?.warnings ?? [];
    if (!warnings.length) {
      return '';
    }
    const base = this.shortWarningText(warnings[0]);
    return warnings.length > 1 ? `${base} +${warnings.length - 1}` : base;
  }

  /** Vollständige Überschrift für den Tooltip (title-Attribut). */
  warningTitle(): string {
    return (this.overview?.warnings ?? [])
      .map(w => w.headline?.trim() || w.event?.trim() || 'Warnung')
      .join('\n');
  }

  private shortWarningText(warning: WeatherWarning): string {
    const event = warning.event?.trim();
    if (event) {
      return event
        .toLocaleLowerCase('de-DE')
        .replace(/(^|\s)\p{L}/gu, match => match.toUpperCase());
    }
    return warning.headline?.trim() || 'Warnung';
  }
}
