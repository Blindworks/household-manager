import {
  AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription } from 'rxjs';
import * as L from 'leaflet';
import { ChargingService } from '../../services/charging.service';
import { ChargePoint, ChargingStation, ChargingStationsResponse } from '../../models/charging.model';
import {
  formatDistance, formatOccupiedDuration, formatPower, formatPrice, sortStations, stationTone
} from '../../shared/charging-status.util';
import { homeIcon, stationIcon, stationPopupText } from '../../shared/charging-map.util';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();

/** Website-Seite "Ladesaeulen": gleiche Karte/Liste wie am Tablet, plus Favorisieren (MEMBER). */
@Component({
  selector: 'app-charging',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './charging.component.html',
  styleUrl: './charging.component.scss'
})
export class ChargingComponent implements OnInit, AfterViewInit, OnDestroy {
  private static readonly REFRESH_INTERVAL_MS = 60_000;

  private readonly chargingService = inject(ChargingService);

  @ViewChild('mapContainer') private mapContainer?: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private markerLayer?: L.LayerGroup;
  private markers = new Map<string, L.Marker>();
  /** Nachgeladene Ladepunkte je Station (die Umkreisliste liefert nur Zaehler). */
  private loadedChargePoints = new Map<string, ChargePoint[]>();
  private loadingChargePoints = new Set<string>();
  private viewInitialized = false;
  private refreshTimer: number | null = null;
  private pendingRequest: Subscription | null = null;

  data: ChargingStationsResponse | null = null;
  error: string | null = null;
  actionError: string | null = null;
  refreshing = false;
  busyStationId: string | null = null;
  now = new Date();

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(() => this.load(true), ChargingComponent.REFRESH_INTERVAL_MS);
  }

  ngAfterViewInit(): void {
    this.viewInitialized = true;
    this.renderMap();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
    }
    this.pendingRequest?.unsubscribe();
    this.map?.remove();
    this.map = undefined;
  }

  get stations(): ChargingStation[] {
    if (!this.data) {
      return [];
    }
    return sortStations(this.data.stations.map(s => this.withLoadedPoints(s)));
  }

  private withLoadedPoints(station: ChargingStation): ChargingStation {
    if (station.chargePoints) {
      return station;
    }
    const loaded = this.loadedChargePoints.get(station.stationId);
    return loaded ? { ...station, chargePoints: loaded } : station;
  }

  /** Antippen in Liste oder Karte: Ladepunkte nachladen, Popup danach aktualisieren. */
  showDetails(stationId: string): void {
    const marker = this.markers.get(stationId);
    if (marker && this.map) {
      this.map.panTo(marker.getLatLng(), { animate: true });
      marker.openPopup();
    }
    this.ensureChargePoints(stationId);
  }

  private ensureChargePoints(stationId: string): void {
    const station = this.data?.stations.find(s => s.stationId === stationId);
    if (!station || station.chargePoints || this.loadedChargePoints.has(stationId)
      || this.loadingChargePoints.has(stationId)) {
      return;
    }
    this.loadingChargePoints.add(stationId);
    this.chargingService.getChargePoints(stationId).subscribe({
      next: points => {
        this.loadingChargePoints.delete(stationId);
        this.loadedChargePoints.set(stationId, points);
        const marker = this.markers.get(stationId);
        const merged = this.stations.find(s => s.stationId === stationId);
        if (marker && merged) {
          marker.setPopupContent(stationPopupText(merged, this.now));
        }
      },
      error: (err: HttpErrorResponse) => {
        this.loadingChargePoints.delete(stationId);
        console.error('Ladepunkte konnten nicht geladen werden:', err);
      }
    });
  }

  refreshNow(): void {
    this.refreshing = true;
    this.actionError = null;
    this.chargingService.refresh().subscribe({
      next: response => { this.refreshing = false; this.apply(response); },
      error: (err: HttpErrorResponse) => {
        this.refreshing = false;
        this.actionError = err.error?.message ?? 'Aktualisierung fehlgeschlagen.';
      }
    });
  }

  /**
   * Nach der Antwort wird der komplette Stand uebernommen (kein lokales Umschalten): ein
   * neuer Favorit bringt Ladepunkt-Daten mit, die es vorher nicht gab.
   */
  toggleFavorite(station: ChargingStation): void {
    this.busyStationId = station.stationId;
    this.actionError = null;
    const call = station.favorite
      ? this.chargingService.removeFavorite(station.stationId)
      : this.chargingService.addFavorite(station.stationId);
    call.subscribe({
      next: response => { this.busyStationId = null; this.apply(response); },
      error: (err: HttpErrorResponse) => {
        this.busyStationId = null;
        this.actionError = err.error?.message ?? 'Favorit konnte nicht geändert werden.';
      }
    });
  }

  tone(station: ChargingStation): string {
    return stationTone(station);
  }

  power(kw: number | undefined): string {
    return formatPower(kw);
  }

  distance(meters: number): string {
    return formatDistance(meters);
  }

  price(eurPerKwh: number | undefined): string {
    return formatPrice(eurPerKwh);
  }

  duration(point: ChargePoint): string {
    return formatOccupiedDuration(point.occupiedSince, point.minimumDuration, this.now);
  }

  pointLabel(point: ChargePoint): string {
    switch (point.status) {
      case 'FREE': return 'frei';
      case 'OCCUPIED': return 'belegt';
      case 'OUT_OF_SERVICE': return 'außer Betrieb';
      case 'UNKNOWN': return 'unbekannt';
    }
  }

  private load(silent: boolean): void {
    if (!silent) {
      this.error = null;
    }
    this.pendingRequest?.unsubscribe();
    this.pendingRequest = this.chargingService.getStations().subscribe({
      next: response => this.apply(response),
      error: (err: HttpErrorResponse) => {
        console.error('Ladesaeulen konnten nicht geladen werden:', err);
        if (!silent) {
          this.error = 'Ladesäulen konnten nicht geladen werden.';
        }
      }
    });
  }

  private apply(response: ChargingStationsResponse): void {
    this.data = response;
    this.error = null;
    this.now = new Date();
    this.renderMap();
  }

  private renderMap(): void {
    const container = this.mapContainer?.nativeElement;
    if (!this.viewInitialized || !container || !this.data?.configured || !this.data.home) {
      return;
    }
    const home: L.LatLngExpression = [this.data.home.lat, this.data.home.lon];
    if (!this.map) {
      this.map = L.map(container);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap', maxZoom: 19
      }).addTo(this.map);
      const radiusMeters = (this.data.radiusKm ?? 10) * 1000;
      L.circle(home, { radius: radiusMeters, color: '#0284c7', weight: 1, fillOpacity: 0.04 }).addTo(this.map);
      L.marker(home, { icon: homeIcon(), interactive: false }).addTo(this.map);
      this.markerLayer = L.layerGroup().addTo(this.map);
      this.map.fitBounds(L.latLng(home).toBounds(radiusMeters * 2), { padding: [8, 8] });
    }
    this.markerLayer!.clearLayers();
    this.markers.clear();
    for (const station of this.stations) {
      const marker = L.marker([station.lat, station.lon], { icon: stationIcon(station) })
        .bindPopup(stationPopupText(station, this.now))
        .on('click', () => this.ensureChargePoints(station.stationId))
        .addTo(this.markerLayer!);
      this.markers.set(station.stationId, marker);
    }
  }
}
