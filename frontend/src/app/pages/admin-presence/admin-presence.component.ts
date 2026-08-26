import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { PresenceService } from '../../services/presence.service';
import { HouseholdUserService } from '../../services/household-user.service';
import { HouseholdUser } from '../../models/household-user.model';
import { PresenceDeviceAdmin, PresenceDeviceRequest } from '../../models/presence.model';

/**
 * Zustand des Anlege-/Bearbeiten-Formulars. Bewusst nicht `extends Request`:
 * die Personenauswahl ist im Formular `number | null` (kein Eintrag gewaehlt),
 * im Request dagegen Pflicht. Umwandlung an genau einer Stelle (toRequest).
 */
interface DeviceFormState {
  /** null = Anlegen, sonst die Id des bearbeiteten Geraets. */
  id: number | null;
  userId: number | null;
  name: string;
  host: string;
  active: boolean;
}

function emptyForm(): DeviceFormState {
  return { id: null, userId: null, name: '', host: '', active: true };
}

/**
 * Admin-Seite „Anwesenheit": Karenzzeit plus die Handys, die das Backend alle
 * 30 s per TCP probt. Muster/Interaktionsform: Admin-Seite „Netzwerk-Geräte".
 */
@Component({
  selector: 'app-admin-presence',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admin-presence.component.html',
  styleUrl: './admin-presence.component.scss'
})
export class AdminPresenceComponent implements OnInit {
  private readonly presenceApi = inject(PresenceService);
  private readonly userApi = inject(HouseholdUserService);

  readonly devices = signal<PresenceDeviceAdmin[]>([]);
  readonly users = signal<HouseholdUser[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly settingsMessage = signal<string | null>(null);
  readonly settingsSaving = signal(false);

  form: DeviceFormState = emptyForm();
  /** Karenzzeit-Formularwert; null solange noch nichts geladen ist. */
  graceMinutes: number | null = null;

  /**
   * „Zuletzt gesehen" je Gerät, geschlüsselt nach Geräte-Id.
   *
   * Kommt aus `GET /status` und nicht aus der Geräteliste: der Zeitpunkt lebt nur
   * im Speicher des Backends. Er ist die **einzige** Stelle, an der ein Handy
   * auffällt, dessen WLAN-Adresse gewechselt hat (siehe Spec) — deshalb steht er
   * hier, obwohl die Seite sonst reine Stammdatenpflege ist. Schlägt der Abruf
   * fehl, bleibt die Spalte leer; die Pflege muss trotzdem funktionieren.
   */
  readonly lastSeenByDeviceId = signal<Map<number, string | null>>(new Map());

  ngOnInit(): void {
    this.load();
    this.loadLastSeen();
    this.userApi.list().subscribe({
      next: users => this.users.set(users.filter(user => user.enabled)),
      error: () => this.errorMessage.set('Die Haushaltsmitglieder konnten nicht geladen werden.')
    });
    this.presenceApi.getSettings().subscribe({
      next: settings => (this.graceMinutes = settings.awayGraceMinutes),
      error: () => this.settingsMessage.set('Die Karenzzeit konnte nicht geladen werden.')
    });
  }

  private loadLastSeen(): void {
    this.presenceApi.getStatus().subscribe({
      next: status => {
        const seen = new Map<number, string | null>();
        for (const person of status.persons) {
          for (const device of person.devices) {
            seen.set(device.id, device.lastSeenAt);
          }
        }
        this.lastSeenByDeviceId.set(seen);
      },
      // Bewusst stumm: die Spalte ist Diagnose-Beiwerk, ein Fehler hier darf die
      // Geräteliste nicht mit einer Meldung überlagern.
      error: () => this.lastSeenByDeviceId.set(new Map())
    });
  }

  lastSeenOf(device: PresenceDeviceAdmin): string | null {
    return this.lastSeenByDeviceId().get(device.id) ?? null;
  }

  load(afterLoad?: () => void): void {
    this.presenceApi.getDevices().subscribe({
      next: devices => {
        this.devices.set(devices);
        this.loadFailed.set(false);
        this.loading.set(false);
        afterLoad?.();
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

  displayNameOf(userId: number): string {
    return this.users().find(user => user.id === userId)?.displayName ?? `Person ${userId}`;
  }

  startEdit(device: PresenceDeviceAdmin): void {
    this.errorMessage.set(null);
    this.form = {
      id: device.id,
      userId: device.userId,
      name: device.name,
      host: device.host,
      active: device.active
    };
  }

  resetForm(): void {
    this.form = emptyForm();
    this.errorMessage.set(null);
  }

  save(): void {
    if (this.form.userId === null) {
      this.errorMessage.set('Es ist keine Person ausgewählt.');
      return;
    }
    if (!this.form.name.trim()) {
      this.errorMessage.set('Der Name darf nicht leer sein.');
      return;
    }
    if (!this.form.host.trim()) {
      this.errorMessage.set('Die IP-Adresse darf nicht leer sein.');
      return;
    }
    const request = this.toRequest(this.form, this.form.userId);
    const id = this.form.id;
    this.saving.set(true);
    this.errorMessage.set(null);
    const call = id === null
      ? this.presenceApi.createDevice(request)
      : this.presenceApi.updateDevice(id, request);
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

  /**
   * Sendet IMMER den kompletten Request: ein fehlendes `active` liest der
   * Server als „aktiv", ein Teil-PUT reaktivierte ein deaktiviertes Geraet
   * stillschweigend (Muster Netzwerk-Geräte).
   */
  setActive(device: PresenceDeviceAdmin, active: boolean): void {
    this.errorMessage.set(null);
    this.presenceApi.updateDevice(device.id, {
      userId: device.userId,
      name: device.name,
      host: device.host,
      active
    }).subscribe({
      next: () => {
        if (this.form.id === device.id) {
          this.form.active = active;
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  remove(device: PresenceDeviceAdmin): void {
    if (!confirm(`Gerät „${device.name}“ endgültig löschen?`)) {
      return;
    }
    this.errorMessage.set(null);
    this.presenceApi.deleteDevice(device.id).subscribe({
      next: () => {
        if (this.form.id === device.id) {
          this.resetForm();
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => this.errorMessage.set(this.messageFrom(error))
    });
  }

  saveSettings(): void {
    if (this.graceMinutes === null || this.graceMinutes < 1) {
      this.settingsMessage.set('Die Karenzzeit muss mindestens 1 Minute betragen.');
      return;
    }
    this.settingsSaving.set(true);
    this.settingsMessage.set(null);
    this.presenceApi.updateSettings({ awayGraceMinutes: this.graceMinutes }).subscribe({
      next: settings => {
        this.graceMinutes = settings.awayGraceMinutes;
        this.settingsSaving.set(false);
        this.settingsMessage.set('Gespeichert.');
      },
      error: (error: HttpErrorResponse) => {
        this.settingsSaving.set(false);
        this.settingsMessage.set(this.messageFrom(error));
      }
    });
  }

  private toRequest(state: DeviceFormState, userId: number): PresenceDeviceRequest {
    return {
      userId,
      name: state.name.trim(),
      host: state.host.trim(),
      active: state.active
    };
  }

  private messageFrom(error: HttpErrorResponse): string {
    return error.error?.message ?? 'Fehler bei der Netzwerk-Kommunikation.';
  }
}
