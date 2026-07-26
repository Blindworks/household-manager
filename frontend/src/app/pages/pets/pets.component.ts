import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import * as L from 'leaflet';
import { TractiveService } from '../../services/tractive.service';
import { TractivePet } from '../../models/tractive.model';

/**
 * Leaflet ermittelt die Standard-Marker-Icons ueber eine relative URL zum
 * aktuell ausgefuehrten Skript. Unter dem Angular-Bundler funktioniert diese
 * Ermittlung nicht - die Icons wuerden sonst schweigend fehlen. Deshalb die
 * Icon-URLs hier einmalig explizit setzen, bevor irgendeine Karte entsteht.
 */
function fixLeafletDefaultIcon(): void {
  const iconPrototype = L.Icon.Default.prototype as L.Icon.Default & { _getIconUrl?: unknown };
  delete iconPrototype._getIconUrl;
  L.Icon.Default.mergeOptions({
    iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
    iconUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
    shadowUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png'
  });
}
fixLeafletDefaultIcon();

/** Seite „Hundetracker": Login, Karte und Kacheln je Haustier. */
@Component({
  selector: 'app-pets',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './pets.component.html',
  styleUrl: './pets.component.scss'
})
export class PetsComponent implements OnInit, OnDestroy {
  private readonly tractiveService = inject(TractiveService);

  readonly authenticated = signal(false);
  readonly loading = signal(true);
  readonly errorMessage = signal<string | null>(null);
  readonly pets = signal<TractivePet[]>([]);

  email = '';
  password = '';

  private map?: L.Map;
  private markers = new Map<string, L.Marker>();
  private refreshTimer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.tractiveService.getStatus().subscribe({
      next: status => {
        this.authenticated.set(status.authenticated);
        this.loading.set(false);
        if (status.authenticated) {
          this.startPolling();
        }
      },
      error: () => {
        this.loading.set(false);
        this.errorMessage.set('Status konnte nicht geladen werden.');
      }
    });
  }

  ngOnDestroy(): void {
    this.stopPolling();
    this.map?.remove();
  }

  login(): void {
    this.errorMessage.set(null);
    this.tractiveService.login(this.email, this.password).subscribe({
      next: () => {
        this.password = '';
        this.authenticated.set(true);
        this.startPolling();
      },
      // Ein 401 bedeutet falsche Zugangsdaten; jeder andere Fehler (z. B. 502)
      // bedeutet, dass die Tractive-Cloud nicht erreichbar ist - das darf nicht
      // faelschlich als falsches Passwort dargestellt werden.
      error: (err: HttpErrorResponse) => {
        this.password = '';
        if (err.status === 401) {
          this.errorMessage.set('Anmeldung fehlgeschlagen. Bitte Zugangsdaten pruefen.');
        } else {
          this.errorMessage.set('Tractive ist derzeit nicht erreichbar. Bitte spaeter erneut versuchen.');
        }
      }
    });
  }

  logout(): void {
    this.tractiveService.logout().subscribe({
      next: () => {
        this.stopPolling();
        this.authenticated.set(false);
        this.pets.set([]);
      }
    });
  }

  /** Anzeigetext der Zone. */
  zoneLabel(pet: TractivePet): string {
    if (pet.zone === 'away') {
      return 'Ausserhalb der Zone';
    }
    if (pet.zone === 'unknown') {
      return 'Keine Position';
    }
    return pet.zone;
  }

  private startPolling(): void {
    this.loadPets();
    this.refreshTimer = setInterval(() => this.loadPets(), 60000);
  }

  private stopPolling(): void {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = undefined;
    }
  }

  private loadPets(): void {
    this.tractiveService.getPets().subscribe({
      next: pets => {
        this.pets.set(pets);
        this.renderMap(pets);
      },
      error: () => this.errorMessage.set('Positionen konnten nicht geladen werden.')
    });
  }

  /** Karte beim ersten Datensatz aufbauen und Marker aktualisieren. */
  private renderMap(pets: TractivePet[]): void {
    const located = pets.filter(pet => pet.latitude != null && pet.longitude != null);
    if (located.length === 0) {
      return;
    }
    if (!this.map) {
      const container = document.getElementById('pet-map');
      if (!container) {
        return;
      }
      this.map = L.map(container).setView([located[0].latitude!, located[0].longitude!], 16);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; OpenStreetMap',
        maxZoom: 19
      }).addTo(this.map);
    }
    for (const pet of located) {
      const position: L.LatLngExpression = [pet.latitude!, pet.longitude!];
      const existing = this.markers.get(pet.trackerId);
      if (existing) {
        existing.setLatLng(position);
      } else {
        this.markers.set(pet.trackerId,
          L.marker(position).addTo(this.map).bindPopup(pet.name));
      }
    }
  }
}
