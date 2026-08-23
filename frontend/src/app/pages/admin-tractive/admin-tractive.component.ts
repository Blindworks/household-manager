import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import * as L from 'leaflet';
import { TractiveService } from '../../services/tractive.service';
import { TractiveHomeSettings } from '../../models/tractive-home-settings.model';
import { TractiveAuthStatus } from '../../models/tractive.model';
import { useLocalLeafletIcons } from '../../shared/leaflet-icons.util';

useLocalLeafletIcons();

/** Mitte Deutschlands – nur der Startausschnitt, solange nichts konfiguriert ist. */
const FALLBACK_CENTER: L.LatLngExpression = [51.1657, 10.4515];
const FALLBACK_ZOOM = 6;
const CONFIGURED_ZOOM = 17;

/** Admin-Seite „Hundetracker": Verbindung zu Tractive und Definition von „zu Hause". */
@Component({
  selector: 'app-admin-tractive',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-tractive.component.html',
  styleUrl: './admin-tractive.component.scss'
})
export class AdminTractiveComponent implements OnInit, OnDestroy {
  private readonly tractiveService = inject(TractiveService);

  readonly loading = signal(true);
  readonly saving = signal(false);
  /**
   * Konnte der Bestand nicht geladen werden, bleibt das Formular verborgen. Sonst stuenden
   * dort die hartkodierten Vorgabewerte, und Speichern wuerde die echte Konfiguration
   * ueberschreiben – ein Netzwerkfehler duerfte niemals Daten loeschen.
   */
  readonly loadFailed = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly successMessage = signal<string | null>(null);

  /**
   * Verbindungszustand zur Tractive-Cloud. Die Anmeldung liegt bewusst hier und nicht auf
   * der Hundetracker-Seite: {@code POST /v1/tractive/login} ist ADMIN-only, ein Formular
   * auf der fuer alle Angemeldeten offenen Seite haette fuer MEMBER und das Wandtablet
   * immer nur einen 403 erzeugt – angezeigt als "Tractive nicht erreichbar".
   */
  readonly connected = signal(false);
  readonly connectionChecked = signal(false);
  readonly connectedEmail = signal<string | null>(null);
  readonly tokenExpiresAt = signal<string | null>(null);
  readonly authError = signal<string | null>(null);
  readonly authBusy = signal(false);

  email = '';
  password = '';

  settings: TractiveHomeSettings = {
    homeLatitude: null,
    homeLongitude: null,
    homeRadiusMeters: 100,
    homeArrivalRadiusMeters: 500,
    poweredOffAfterMinutes: 60,
    poweredOffMinBatteryPercent: 15,
    homeZoneName: 'Zuhause'
  };

  private map?: L.Map;
  private marker?: L.Marker;
  private homeCircle?: L.Circle;
  private arrivalCircle?: L.Circle;

  ngOnInit(): void {
    // Bewusst zwei unabhaengige Abrufe: die Zuhause-Definition laesst sich auch ohne
    // bestehende Tractive-Verbindung pflegen, und ein Fehler in einem der beiden darf
    // den anderen Bereich nicht mit ausblenden.
    this.loadConnectionStatus();

    this.tractiveService.getHomeSettings().subscribe({
      next: settings => {
        this.settings = settings;
        this.loading.set(false);
        // Erst nach dem Rendern des Containers: die Karte braucht ein Element im DOM.
        setTimeout(() => this.initMap());
      },
      error: () => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.errorMessage.set('Einstellungen konnten nicht geladen werden.');
      }
    });
  }

  login(): void {
    this.authBusy.set(true);
    this.authError.set(null);
    this.tractiveService.login(this.email, this.password).subscribe({
      next: status => {
        this.password = '';
        this.authBusy.set(false);
        this.applyStatus(status);
      },
      // Ein 401 bedeutet falsche Zugangsdaten; jeder andere Fehler bedeutet, dass die
      // Tractive-Cloud nicht erreichbar ist - das darf nicht faelschlich als falsches
      // Passwort dargestellt werden.
      error: (err: HttpErrorResponse) => {
        this.password = '';
        this.authBusy.set(false);
        this.authError.set(err.status === 401
          ? 'Anmeldung fehlgeschlagen. Bitte Zugangsdaten pruefen.'
          : 'Tractive ist derzeit nicht erreichbar. Bitte spaeter erneut versuchen.');
      }
    });
  }

  logout(): void {
    this.authBusy.set(true);
    this.authError.set(null);
    this.tractiveService.logout().subscribe({
      next: () => {
        this.authBusy.set(false);
        this.connected.set(false);
        this.connectedEmail.set(null);
        this.tokenExpiresAt.set(null);
      },
      error: () => {
        this.authBusy.set(false);
        this.authError.set('Abmelden fehlgeschlagen.');
      }
    });
  }

  private loadConnectionStatus(): void {
    this.tractiveService.getStatus().subscribe({
      next: status => this.applyStatus(status),
      error: () => {
        this.connectionChecked.set(true);
        this.authError.set('Verbindungszustand konnte nicht geladen werden.');
      }
    });
  }

  private applyStatus(status: TractiveAuthStatus): void {
    this.connectionChecked.set(true);
    this.connected.set(status.authenticated);
    this.connectedEmail.set(status.email ?? null);
    this.tokenExpiresAt.set(status.expiresAt ?? null);
  }

  ngOnDestroy(): void {
    this.map?.remove();
    this.map = undefined;
  }

  get hasCoordinates(): boolean {
    return this.settings.homeLatitude != null && this.settings.homeLongitude != null;
  }

  /** Nach jeder Zahleneingabe: Marker und Kreise der Karte nachziehen. */
  onValueChange(): void {
    this.successMessage.set(null);
    this.renderHome();
  }

  save(): void {
    this.saving.set(true);
    this.errorMessage.set(null);
    this.successMessage.set(null);
    this.tractiveService.saveHomeSettings(this.settings).subscribe({
      next: saved => {
        this.settings = saved;
        this.saving.set(false);
        this.successMessage.set('Gespeichert. Die Entitaet folgt beim naechsten Abruf (bis zu 1 Minute).');
        this.renderHome();
      },
      // Die 400-Meldung des Servers ist die praezisere – sie nennt das konkrete Feld.
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

  private initMap(): void {
    const container = document.getElementById('home-map');
    if (!container || this.map) {
      return;
    }
    const center: L.LatLngExpression = this.hasCoordinates
      ? [this.settings.homeLatitude!, this.settings.homeLongitude!]
      : FALLBACK_CENTER;
    this.map = L.map(container).setView(center, this.hasCoordinates ? CONFIGURED_ZOOM : FALLBACK_ZOOM);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '&copy; OpenStreetMap',
      maxZoom: 19
    }).addTo(this.map);
    this.map.on('click', (event: L.LeafletMouseEvent) => {
      this.settings.homeLatitude = Number(event.latlng.lat.toFixed(6));
      this.settings.homeLongitude = Number(event.latlng.lng.toFixed(6));
      this.successMessage.set(null);
      this.renderHome();
    });
    this.renderHome();
  }

  /** Marker und beide Radien neu zeichnen; ohne Koordinaten werden sie entfernt. */
  private renderHome(): void {
    if (!this.map) {
      return;
    }
    if (!this.hasCoordinates) {
      this.marker?.remove();
      this.homeCircle?.remove();
      this.arrivalCircle?.remove();
      this.marker = undefined;
      this.homeCircle = undefined;
      this.arrivalCircle = undefined;
      return;
    }
    const position: L.LatLngExpression = [this.settings.homeLatitude!, this.settings.homeLongitude!];

    this.marker ? this.marker.setLatLng(position)
      : (this.marker = L.marker(position).addTo(this.map));

    // Der Ankunftsradius wird nie kleiner als der Home-Radius gezeichnet – genau so
    // rechnet auch das Backend (effectiveArrivalRadiusMeters).
    const arrivalRadius = Math.max(this.settings.homeArrivalRadiusMeters, this.settings.homeRadiusMeters);

    if (this.arrivalCircle) {
      this.arrivalCircle.setLatLng(position).setRadius(arrivalRadius);
    } else {
      this.arrivalCircle = L.circle(position, {
        radius: arrivalRadius, color: '#f59e0b', weight: 1, fillOpacity: 0.05
      }).addTo(this.map);
    }
    if (this.homeCircle) {
      this.homeCircle.setLatLng(position).setRadius(this.settings.homeRadiusMeters);
    } else {
      this.homeCircle = L.circle(position, {
        radius: this.settings.homeRadiusMeters, color: '#16a34a', weight: 2, fillOpacity: 0.12
      }).addTo(this.map);
    }
  }
}
