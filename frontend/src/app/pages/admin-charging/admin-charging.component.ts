import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import * as L from 'leaflet';
import { ChargingService } from '../../services/charging.service';
import { TractiveService } from '../../services/tractive.service';
import { ChargingSettings, ChargingStation } from '../../models/charging.model';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();

const FALLBACK_CENTER: L.LatLngExpression = [51.1657, 10.4515];
const FALLBACK_ZOOM = 6;
const CONFIGURED_ZOOM = 12;

/**
 * Admin-Seite "Ladesaeulen": Zuhause (Leaflet-Klick), Radius, Mindestleistung, Favoritenliste.
 * Die Schranken 1-50 km / 0-400 kW stehen ZWEIMAL: verbindlich in ChargingSettingsService
 * (Backend), hier nur als Bedienhilfe - wer eine aendert, zieht die andere nach.
 */
@Component({
  selector: 'app-admin-charging',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-charging.component.html',
  styleUrl: './admin-charging.component.scss'
})
export class AdminChargingComponent implements OnInit, OnDestroy {
  private readonly chargingService = inject(ChargingService);
  private readonly tractiveService = inject(TractiveService);

  readonly radiusMin = 1;
  readonly radiusMax = 50;
  readonly powerMin = 0;
  readonly powerMax = 400;

  readonly loading = signal(true);
  readonly saving = signal(false);
  /** Bei Ladefehler bleibt das Formular verborgen - sonst ueberschriebe "Speichern" echte Werte mit Vorgaben. */
  readonly loadFailed = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);
  readonly favoriteError = signal<string | null>(null);

  settings: ChargingSettings = { homeLatitude: null, homeLongitude: null, radiusKm: 10, minPowerKw: 50 };
  favorites: ChargingStation[] = [];

  private map?: L.Map;
  private marker?: L.Marker;
  private circle?: L.Circle;

  ngOnInit(): void {
    this.chargingService.getSettings().subscribe({
      next: settings => {
        this.settings = settings;
        this.loading.set(false);
        setTimeout(() => this.initMap());
      },
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.errorMessage.set('Einstellungen konnten nicht geladen werden.');
      }
    });
    this.loadFavorites();
  }

  ngOnDestroy(): void {
    this.map?.remove();
    this.map = undefined;
  }

  get hasCoordinates(): boolean {
    return this.settings.homeLatitude != null && this.settings.homeLongitude != null;
  }

  get canSave(): boolean {
    const r = this.settings.radiusKm;
    const p = this.settings.minPowerKw;
    return Number.isFinite(r) && r >= this.radiusMin && r <= this.radiusMax
      && Number.isFinite(p) && p >= this.powerMin && p <= this.powerMax;
  }

  onValueChange(): void {
    this.successMessage.set(null);
    this.renderHome();
  }

  save(): void {
    this.saving.set(true);
    this.errorMessage.set(null);
    this.successMessage.set(null);
    this.chargingService.saveSettings(this.settings).subscribe({
      next: saved => {
        this.settings = saved;
        this.saving.set(false);
        this.successMessage.set('Gespeichert. Der Umkreis wird beim nächsten Abruf neu geladen (bis zu 5 Minuten).');
        this.renderHome();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.errorMessage.set(err.error?.message ?? 'Speichern fehlgeschlagen.');
      }
    });
  }

  clearCoordinates(): void {
    this.settings.homeLatitude = null;
    this.settings.homeLongitude = null;
    this.renderHome();
  }

  /** Einmalige Vorbelegung aus der Hundetracker-Definition; danach eigenstaendige Werte. */
  adoptTractiveHome(): void {
    this.errorMessage.set(null);
    this.tractiveService.getHomeSettings().subscribe({
      next: home => {
        if (home.homeLatitude == null || home.homeLongitude == null) {
          this.errorMessage.set('Im Hundetracker ist kein Zuhause hinterlegt.');
          return;
        }
        this.settings.homeLatitude = home.homeLatitude;
        this.settings.homeLongitude = home.homeLongitude;
        this.successMessage.set(null);
        this.renderHome(true);
      },
      error: () => this.errorMessage.set('Hundetracker-Zuhause konnte nicht geladen werden.')
    });
  }

  removeFavorite(stationId: string): void {
    this.favoriteError.set(null);
    this.chargingService.removeFavorite(stationId).subscribe({
      next: response => { this.favorites = response.stations.filter(s => s.favorite); },
      error: (err: HttpErrorResponse) =>
        this.favoriteError.set(err.error?.message ?? 'Favorit konnte nicht entfernt werden.')
    });
  }

  private loadFavorites(): void {
    this.chargingService.getStations().subscribe({
      next: response => { this.favorites = response.stations.filter(s => s.favorite); },
      error: () => this.favoriteError.set('Favoriten konnten nicht geladen werden.')
    });
  }

  private initMap(): void {
    const container = document.getElementById('charging-home-map');
    if (!container || this.map) {
      return;
    }
    const center: L.LatLngExpression = this.hasCoordinates
      ? [this.settings.homeLatitude!, this.settings.homeLongitude!] : FALLBACK_CENTER;
    this.map = L.map(container).setView(center, this.hasCoordinates ? CONFIGURED_ZOOM : FALLBACK_ZOOM);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap', maxZoom: 19
    }).addTo(this.map);
    this.map.on('click', (event: L.LeafletMouseEvent) => {
      this.settings.homeLatitude = Number(event.latlng.lat.toFixed(6));
      this.settings.homeLongitude = Number(event.latlng.lng.toFixed(6));
      this.successMessage.set(null);
      this.renderHome();
    });
    this.renderHome();
  }

  private renderHome(recenter = false): void {
    if (!this.map) {
      return;
    }
    if (!this.hasCoordinates) {
      this.marker?.remove();
      this.circle?.remove();
      this.marker = undefined;
      this.circle = undefined;
      return;
    }
    const position: L.LatLngExpression = [this.settings.homeLatitude!, this.settings.homeLongitude!];
    const radiusMeters = this.settings.radiusKm * 1000;
    this.marker ? this.marker.setLatLng(position) : (this.marker = L.marker(position).addTo(this.map));
    if (this.circle) {
      this.circle.setLatLng(position).setRadius(radiusMeters);
    } else {
      this.circle = L.circle(position, { radius: radiusMeters, color: '#0284c7', weight: 2, fillOpacity: 0.08 })
        .addTo(this.map);
    }
    if (recenter) {
      this.map.setView(position, CONFIGURED_ZOOM);
    }
  }
}
