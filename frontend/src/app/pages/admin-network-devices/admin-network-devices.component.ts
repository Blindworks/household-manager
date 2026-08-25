import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { NetworkService } from '../../services/network.service';
import { NetworkDeviceAdminResponse, NetworkDeviceRequest } from '../../models/network.model';

/**
 * Zustand des Anlege-/Bearbeiten-Formulars.
 *
 * Bewusst nicht `extends NetworkDeviceRequest`: Port und Reihenfolge sind im Formular
 * `number | null` (ein geleertes Zahlenfeld liefert bei Angulars NumberValueAccessor
 * `null`, nicht 0 oder NaN), im Request dagegen optional/number. Die Umwandlung passiert
 * an genau einer Stelle — {@link AdminNetworkDevicesComponent.toRequest}.
 */
interface DeviceFormState {
  /** null = Anlegen, sonst die Id des bearbeiteten Geraets. */
  id: number | null;
  name: string;
  host: string;
  tcpPort: number | null;
  sortOrder: number | null;
  active: boolean;
}

/**
 * Abstand zwischen zwei vorgeschlagenen Reihenfolgen. Laesst Platz, um spaeter ein Geraet
 * dazwischen zu schieben, ohne alle anderen anzufassen (Muster Kalender-Kategorien).
 */
const SORT_ORDER_STEP = 10;

function emptyForm(sortOrder: number): DeviceFormState {
  return { id: null, name: '', host: '', tcpPort: null, sortOrder, active: true };
}

/**
 * Admin-Seite „Netzwerk-Geräte": Pflege der LAN-Geräte, die das Backend minütlich per TCP
 * prüft. Muster/Interaktionsform an der Admin-Seite „Kalender-Kategorien" ausgerichtet.
 */
@Component({
  selector: 'app-admin-network-devices',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-network-devices.component.html',
  styleUrl: './admin-network-devices.component.scss'
})
export class AdminNetworkDevicesComponent implements OnInit {
  private readonly networkApi = inject(NetworkService);

  readonly devices = signal<NetworkDeviceAdminResponse[]>([]);
  /**
   * Nur der erste Abruf blendet die Tabelle aus. Spaetere Aktualisierungen lassen sie
   * stehen, sonst springt das Layout bei jeder Aktion.
   */
  readonly loading = signal(true);
  /**
   * Bei fehlgeschlagenem Laden bleibt die Tabelle verborgen. Sonst behauptete sie mit
   * „Noch keine Geräte angelegt." das Gegenteil dessen, was der Fall ist.
   */
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);

  form: DeviceFormState = emptyForm(SORT_ORDER_STEP);

  /**
   * Zuletzt vorgeschlagene Reihenfolge. Nur solange das Feld genau diesen Wert traegt,
   * gilt es als unberuehrt (Muster Kalender-Kategorien).
   */
  private lastProposedSortOrder = SORT_ORDER_STEP;

  ngOnInit(): void {
    this.load();
  }

  /**
   * Laedt die Liste neu. `afterLoad` laeuft, sobald die Antwort da ist — auch im
   * Fehlerfall.
   *
   * `loading` wird hier bewusst nicht wieder auf true gesetzt: es markiert nur den
   * allerersten Abruf.
   */
  load(afterLoad?: () => void): void {
    this.networkApi.getDevices().subscribe({
      next: devices => {
        this.devices.set(devices);
        this.loadFailed.set(false);
        this.loading.set(false);
        afterLoad?.();
        this.proposeSortOrder();
      },
      error: (error: HttpErrorResponse) => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.errorMessage.set(this.messageFrom(error));
        afterLoad?.();
      }
    });
  }

  get editing(): boolean {
    return this.form.id !== null;
  }

  startEdit(device: NetworkDeviceAdminResponse): void {
    this.errorMessage.set(null);
    this.form = {
      id: device.id,
      name: device.name,
      host: device.host,
      tcpPort: device.tcpPort,
      sortOrder: device.sortOrder,
      active: device.active
    };
  }

  resetForm(): void {
    this.lastProposedSortOrder = this.nextSortOrder();
    this.form = emptyForm(this.lastProposedSortOrder);
    this.errorMessage.set(null);
  }

  /**
   * Uebernimmt den Reihenfolge-Vorschlag in ein noch unberuehrtes Anlege-Formular.
   * Beim Bearbeiten und bei einem selbst eingetragenen Wert bleibt das Feld unangetastet.
   */
  private proposeSortOrder(): void {
    if (this.form.id !== null || this.form.sortOrder !== this.lastProposedSortOrder) {
      return;
    }
    this.lastProposedSortOrder = this.nextSortOrder();
    this.form.sortOrder = this.lastProposedSortOrder;
  }

  /** Vorschlag fuer die Reihenfolge eines neuen Geraets: hinter allen bestehenden. */
  private nextSortOrder(): number {
    const highest = this.devices()
      .reduce((max, device) => Math.max(max, device.sortOrder), 0);
    return highest + SORT_ORDER_STEP;
  }

  save(): void {
    if (!this.form.name.trim()) {
      this.errorMessage.set('Der Name darf nicht leer sein.');
      return;
    }
    if (!this.form.host.trim()) {
      this.errorMessage.set('Der Host darf nicht leer sein.');
      return;
    }
    const request = this.toRequest(this.form);
    const id = this.form.id;
    this.saving.set(true);
    this.errorMessage.set(null);
    const call = id === null
      ? this.networkApi.createDevice(request)
      : this.networkApi.updateDevice(id, request);
    call.subscribe({
      next: () => this.load(() => {
        this.saving.set(false);
        this.resetForm();
      }),
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        this.errorMessage.set(this.messageFrom(error));
      }
    });
  }

  setActive(device: NetworkDeviceAdminResponse, active: boolean): void {
    this.errorMessage.set(null);
    this.networkApi.updateDevice(device.id, this.requestFrom(device, active)).subscribe({
      next: () => {
        // Steht dasselbe Geraet gerade im Formular, muss der Schalter dort mitwandern.
        if (this.form.id === device.id) {
          this.form.active = active;
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  remove(device: NetworkDeviceAdminResponse): void {
    if (!confirm(`Gerät „${device.name}“ endgültig löschen?`)) {
      return;
    }
    this.errorMessage.set(null);
    this.networkApi.deleteDevice(device.id).subscribe({
      next: () => {
        // Stand das geloeschte Geraet im Formular, ist dessen Id jetzt tot.
        if (this.form.id === device.id) {
          this.resetForm();
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  /** Formularzustand -> Request. */
  private toRequest(state: DeviceFormState): NetworkDeviceRequest {
    return {
      name: state.name.trim(),
      host: state.host.trim(),
      tcpPort: state.tcpPort,
      // Ein geleertes Zahlenfeld liefert null; die 0 ist der Default der Spalte.
      sortOrder: state.sortOrder ?? 0,
      active: state.active
    };
  }

  /**
   * Vollstaendiger Request aus einem bestehenden Geraet, mit ausgetauschtem `active`.
   *
   * Der Server liest ein fehlendes `active` als „aktiv". Ein Teil-PUT wuerde ein
   * deaktiviertes Geraet also stillschweigend wieder aktivieren. Deshalb wird hier immer
   * der komplette Satz gesendet.
   */
  private requestFrom(device: NetworkDeviceAdminResponse, active: boolean): NetworkDeviceRequest {
    return {
      name: device.name,
      host: device.host,
      tcpPort: device.tcpPort,
      sortOrder: device.sortOrder,
      active
    };
  }

  private messageFrom(error: HttpErrorResponse): string {
    return error.error?.message ?? 'Fehler bei der Netzwerk-Kommunikation.';
  }
}
