import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { WeatherService } from '../../services/weather.service';
import { WeatherOverview } from '../../models/weather.model';
import { weatherSymbol, WeatherSymbol } from '../../shared/weather-icon.util';

/** Kompaktes Wetter-Widget fürs Dashboard, verlinkt auf /weather. */
@Component({
  selector: 'app-weather-widget',
  standalone: true,
  imports: [CommonModule, RouterLink],
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
}
