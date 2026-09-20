import {
  AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Subscription } from 'rxjs';
import * as L from 'leaflet';
import { TabletShellComponent } from '../../components/tablet-shell/tablet-shell.component';
import { ChargingService } from '../../services/charging.service';
import { ChargePoint, ChargingStation, ChargingStationsResponse } from '../../models/charging.model';
import {
  formatDistance, formatOccupiedDuration, formatPower, formatPrice, sortStations, stationTone
} from '../../shared/charging-status.util';
import { homeIcon, stationIcon, stationPopupText } from '../../shared/charging-map.util';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();

/**
 * Ladesaeulen-Uebersicht fuer das Wandtablet: Karte links, Liste rechts (Favoriten mit
 * Ladepunkten und Belegungsdauer zuerst, dann die uebrigen nach Entfernung).
 */
@Component({
  selector: 'app-tablet-charging',
  standalone: true,
  imports: [CommonModule, TabletShellComponent],
  templateUrl: './tablet-charging.component.html',
  styleUrl: './tablet-charging.component.scss'
})
export class TabletChargingComponent implements OnInit, AfterViewInit, OnDestroy {
  private static readonly REFRESH_INTERVAL_MS = 60_000;
  /** Die Dauer laeuft zwischen zwei Abrufen weiter - minuetlich neu rechnen. */
  private static readonly TICK_INTERVAL_MS = 60_000;

  private readonly chargingService = inject(ChargingService);

  @ViewChild('mapContainer') private mapContainer?: ElementRef<HTMLDivElement>;

  private map?: L.Map;
  private markerLayer?: L.LayerGroup;
  /** Marker je Station, damit eine Auswahl in der Liste den Pin auf der Karte findet. */
  private markers = new Map<string, L.Marker>();
  private homeLayer?: L.LayerGroup;
  private viewInitialized = false;
  private refreshTimer: number | null = null;
  private tickTimer: number | null = null;
  private pendingRequest: Subscription | null = null;

  data: ChargingStationsResponse | null = null;
  error: string | null = null;
  refreshing = false;
  refreshError: string | null = null;
  /** Hebt die zuletzt angetippte Station in der Liste hervor. */
  selectedStationId: string | null = null;
  /** Wird minuetlich hochgezaehlt, damit die Dauer-Getter neu ausgewertet werden. */
  now = new Date();

  ngOnInit(): void {
    this.load(false);
    this.refreshTimer = window.setInterval(() => this.reload(), TabletChargingComponent.REFRESH_INTERVAL_MS);
    this.tickTimer = window.setInterval(() => { this.now = new Date(); }, TabletChargingComponent.TICK_INTERVAL_MS);
  }

  ngAfterViewInit(): void {
    this.viewInitialized = true;
    this.renderMap();
  }

  ngOnDestroy(): void {
    if (this.refreshTimer !== null) {
      window.clearInterval(this.refreshTimer);
    }
    if (this.tickTimer !== null) {
      window.clearInterval(this.tickTimer);
    }
    this.pendingRequest?.unsubscribe();
    this.map?.remove();
    this.map = undefined;
  }

  get stations(): ChargingStation[] {
    return this.data ? sortStations(this.data.stations) : [];
  }

  get lastPolledLabel(): string {
    if (!this.data?.lastPolledAt) {
      return 'Noch keine Daten';
    }
    const at = new Date(this.data.lastPolledAt);
    return `Stand ${at.getHours().toString().padStart(2, '0')}:${at.getMinutes().toString().padStart(2, '0')}`;
  }

  reload(): void {
    this.load(true);
  }

  refreshNow(): void {
    this.refreshing = true;
    this.refreshError = null;
    this.chargingService.refresh().subscribe({
      next: response => {
        this.refreshing = false;
        this.apply(response);
      },
      error: (err: HttpErrorResponse) => {
        this.refreshing = false;
        this.refreshError = err.error?.message ?? 'Aktualisierung fehlgeschlagen.';
      }
    });
  }

  /**
   * Auswahl aus Liste oder Karte: hebt die Zeile UND den Pin hervor, oeffnet das Popup und
   * schwenkt die Karte auf den Standort - ohne den Zoom anzufassen (ein gezoomter Blick soll
   * bleiben, Muster wie beim Refresh).
   */
  select(stationId: string, options: { fromMap?: boolean } = {}): void {
    this.selectedStationId = stationId;
    this.applySelectionToMarkers();
    const marker = this.markers.get(stationId);
    if (!marker || !this.map) {
      return;
    }
    if (!options.fromMap) {
      this.map.panTo(marker.getLatLng(), { animate: true });
      marker.openPopup();
    }
  }

  /** Setzt die Hervorhebungsklasse auf genau einem Pin; ueberlebt auch den Marker-Austausch. */
  private applySelectionToMarkers(): void {
    for (const [stationId, marker] of this.markers) {
      const element = marker.getElement();
      if (element) {
        element.classList.toggle('charging-marker--selected', stationId === this.selectedStationId);
      }
    }
  }

  tone(station: ChargingStation): string {
    return stationTone(station);
  }

  pointTone(point: ChargePoint): string {
    return point.status === 'FREE' ? 'free' : point.status === 'OCCUPIED' ? 'busy' : 'unknown';
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

  /** Nur fuer Tests: Zugriff auf die Karte, um Ausschnitt-Erhalt zu pruefen. */
  mapForTest(): L.Map | undefined {
    return this.map;
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

  /**
   * Karte einmal anlegen, Ausschnitt nur beim ersten Mal setzen; danach werden nur die
   * Marker ausgetauscht - ein Refresh darf einen gezoomten Blick nicht zuruecksetzen.
   */
  private renderMap(): void {
    const container = this.mapContainer?.nativeElement;
    if (!this.viewInitialized || !container || !this.data?.configured || !this.data.home) {
      return;
    }
    const home: L.LatLngExpression = [this.data.home.lat, this.data.home.lon];
    if (!this.map) {
      this.map = L.map(container, { zoomControl: false });
      // OSM-Standardkacheln; die dunkle Tesla-Optik entsteht per CSS-Filter auf der Kachelebene
      // (siehe SCSS). Dunkle Kachel-Anbieter (CARTO) verlangen inzwischen einen API-Key.
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap', maxZoom: 19
      }).addTo(this.map);
      this.homeLayer = L.layerGroup().addTo(this.map);
      this.markerLayer = L.layerGroup().addTo(this.map);
      const radiusMeters = (this.data.radiusKm ?? 10) * 1000;
      L.circle(home, { radius: radiusMeters, color: 'rgba(255, 255, 255, 0.45)', weight: 1, dashArray: '6 6', fillOpacity: 0.03 }).addTo(this.homeLayer);
      L.marker(home, { icon: homeIcon(), interactive: false }).addTo(this.homeLayer);
      this.map.fitBounds(L.latLng(home).toBounds(radiusMeters * 2), { padding: [8, 8] });
    }
    this.markerLayer!.clearLayers();
    this.markers.clear();
    for (const station of this.data.stations) {
      const marker = L.marker([station.lat, station.lon], { icon: stationIcon(station) })
        .bindPopup(stationPopupText(station))
        .on('click', () => this.select(station.stationId, { fromMap: true }))
        .addTo(this.markerLayer!);
      this.markers.set(station.stationId, marker);
    }
    // Nach dem Austausch tragen die neuen Pins die Auswahl noch nicht.
    this.applySelectionToMarkers();
  }
}
