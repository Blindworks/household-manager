import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { PresenceService } from '../../services/presence.service';
import { HouseholdUserService } from '../../services/household-user.service';
import { HouseholdUser } from '../../models/household-user.model';
import {
  PresenceDeviceAdmin,
  PresenceDeviceRequest,
  PresenceStatusResponse
} from '../../models/presence.model';

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
 * Eine waehlbare Person im Dropdown; das Label traegt ein etwaiges
 * "(deaktiviert)"-Suffix bereits (Muster Kalender-Termindialog).
 */
interface UserOption {
  id: number;
  label: string;
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
  /** Alle Haushaltsmitglieder, aktiv UND deaktiviert (siehe {@link displayNameOf}). */
  readonly users = signal<HouseholdUser[]>([]);
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
  /** Fehler rund um die Geraeteliste (Laden/Anlegen/Aendern/Loeschen/Umschalten). */
  readonly errorMessage = signal<string | null>(null);
  /**
   * Eigenes Signal fuer einen fehlgeschlagenen Nutzer-Abruf. Bewusst getrennt von
   * `errorMessage`: teilten sich beide ein Signal, wuerde ein Klick auf irgendeine
   * Geraete-Aktion (die `errorMessage` vorsorglich auf null setzt) die einzige
   * Erklaerung dafuer loeschen, warum die Personenauswahl leer ist.
   */
  readonly usersMessage = signal<string | null>(null);
  readonly settingsMessage = signal<string | null>(null);
  readonly settingsMessageType = signal<'success' | 'error' | null>(null);
  readonly settingsSaving = signal(false);
  /** Id des Geraets, dessen Aktiv-Umschalter gerade einen PUT laufen hat. */
  readonly togglingDeviceId = signal<number | null>(null);
  /** Laeuft gerade ein manueller Abruf (POST /refresh)? Sperrt den Knopf gegen Doppelklicks. */
  readonly refreshing = signal(false);
  /**
   * Eigenes Signal fuer den manuellen Abruf. Bewusst getrennt von `errorMessage`:
   * ein Fehlschlag hier bedeutet nicht, dass die Geraeteliste kaputt ist, und ein
   * Klick auf eine Geraete-Aktion darf diese Meldung nicht mit weglöschen (Muster
   * `usersMessage`).
   */
  readonly refreshMessage = signal<string | null>(null);
  /** Faerbung von {@link refreshMessage} (Muster `settingsMessageType`). */
  readonly refreshMessageType = signal<'success' | 'error' | null>(null);

  form: DeviceFormState = emptyForm();
  /** Karenzzeit-Formularwert; null solange noch nichts geladen ist. */
  graceMinutes: number | null = null;

  /**
   * „Zuletzt gesehen" je Gerät, geschlüsselt nach Geräte-Id.
   *
   * Kommt aus `GET /status` und nicht aus der Geräteliste: der Zeitpunkt lebt nur
   * im Speicher des Backends. Er ist die **einzige** Stelle, an der ein Handy
   * auffällt, dessen WLAN-Adresse gewechselt hat (siehe Spec) — deshalb wird er bei
   * jedem {@link load} erneut geholt, nicht nur einmal beim Seitenaufbau. Schlägt
   * der Abruf fehl, bleibt die Spalte leer; die Pflege muss trotzdem funktionieren.
   */
  readonly lastSeenByDeviceId = signal<Map<number, string | null>>(new Map());

  ngOnInit(): void {
    this.load();
    this.userApi.list().subscribe({
      next: users => this.users.set(users),
      error: () => this.usersMessage.set('Die Haushaltsmitglieder konnten nicht geladen werden.')
    });
    this.presenceApi.getSettings().subscribe({
      next: settings => (this.graceMinutes = settings.awayGraceMinutes),
      error: () => {
        this.settingsMessageType.set('error');
        this.settingsMessage.set('Die Karenzzeit konnte nicht geladen werden.');
      }
    });
  }

  /** Einzige Definition der Status- -> "Zuletzt gesehen"-Abbildung (auch fuer {@link refresh}). */
  private static lastSeenMapOf(status: PresenceStatusResponse): Map<number, string | null> {
    const seen = new Map<number, string | null>();
    for (const person of status.persons) {
      for (const device of person.devices) {
        seen.set(device.id, device.lastSeenAt);
      }
    }
    return seen;
  }

  private loadLastSeen(): void {
    this.presenceApi.getStatus().subscribe({
      next: status => this.lastSeenByDeviceId.set(AdminPresenceComponent.lastSeenMapOf(status)),
      // Bewusst stumm: die Spalte ist Diagnose-Beiwerk, ein Fehler hier darf die
      // Geräteliste nicht mit einer Meldung überlagern.
      error: () => this.lastSeenByDeviceId.set(new Map())
    });
  }

  /**
   * Stoesst den Backend-Probe-Zyklus sofort an, statt auf den naechsten Scheduler-Lauf
   * zu warten. Die Antwort traegt den frischen Status bereits mit - ein zweiter
   * Roundtrip (`load()`) ist nicht noetig, nur die "Zuletzt gesehen"-Spalte wird
   * aktualisiert.
   */
  refresh(): void {
    if (this.refreshing()) {
      return;
    }
    this.refreshing.set(true);
    this.refreshMessage.set(null);
    this.refreshMessageType.set(null);
    this.presenceApi.refresh().subscribe({
      next: status => {
        this.refreshing.set(false);
        this.lastSeenByDeviceId.set(AdminPresenceComponent.lastSeenMapOf(status));
        this.refreshMessageType.set('success');
        this.refreshMessage.set('Geprüft.');
      },
      error: (error: HttpErrorResponse) => {
        this.refreshing.set(false);
        this.refreshMessageType.set('error');
        this.refreshMessage.set(this.messageFrom(error));
      }
    });
  }

  lastSeenOf(device: PresenceDeviceAdmin): string | null {
    return this.lastSeenByDeviceId().get(device.id) ?? null;
  }

  /**
   * Laedt Geraeteliste und „Zuletzt gesehen" neu. `afterLoad` laeuft, sobald die
   * Geraete-Antwort da ist — auch im Fehlerfall.
   *
   * `loading` wird hier bewusst nicht wieder auf true gesetzt: es markiert nur den
   * allerersten Abruf.
   */
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
    this.loadLastSeen();
  }

  get editing(): boolean {
    return this.form.id !== null;
  }

  /**
   * Waehlbare Personen im Formular: alle aktiven plus — falls das Formular gerade
   * eine inzwischen deaktivierte Person traegt — genau diese eine, mit Suffix.
   * Ohne den Sonderfall haette ein bearbeitetes Geraet, dessen Besitzer deaktiviert
   * wurde, im Select keine passende `<option>` mehr: das Feld renderte leer und der
   * Wert wuerde beim Speichern trotzdem still weitergeschrieben (Muster Kalender-
   * Termindialog, `personOptions`).
   */
  get userOptions(): UserOption[] {
    const active = this.users()
      .filter(user => user.enabled)
      .map((user): UserOption => ({ id: user.id, label: user.displayName }));
    const selectedId = this.form.userId;
    if (selectedId === null || active.some(option => option.id === selectedId)) {
      return active;
    }
    const retired = this.users().find(user => user.id === selectedId);
    return retired
      ? [...active, { id: retired.id, label: `${retired.displayName} (deaktiviert)` }]
      : active;
  }

  /**
   * Name zu einer Nutzer-Id, mit „(deaktiviert)"-Suffix falls zutreffend. Fragt
   * bewusst die VOLLE Liste ab, nicht nur die aktiven Nutzer — sonst zeigte die
   * Tabelle fuer ein Geraet eines deaktivierten Mitglieds nur noch „Person 5",
   * obwohl das Backend den echten Namen laengst kennt.
   */
  displayNameOf(userId: number): string {
    const user = this.users().find(candidate => candidate.id === userId);
    if (!user) {
      return `Person ${userId}`;
    }
    return user.enabled ? user.displayName : `${user.displayName} (deaktiviert)`;
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

  /** Nur der Formularzustand; laesst eine gerade gesetzte Fehlermeldung stehen. */
  private clearFormState(): void {
    this.form = emptyForm();
  }

  resetForm(): void {
    this.clearFormState();
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
      // Nur der Formularzustand wird zurueckgesetzt: schlaegt der Reload danach fehl,
      // hat `load()` bereits eine Fehlermeldung gesetzt — die darf hier nicht wieder
      // verschwinden (ein `resetForm()` wuerde sie mit loeschen).
      next: () => this.load(() => {
        this.saving.set(false);
        this.clearFormState();
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
   *
   * `togglingDeviceId` sperrt gegen Doppelklicks: ohne sie sendeten zwei schnelle
   * Klicks zweimal denselben Request, deren Antworten in beliebiger Reihenfolge
   * eintreffen koennten.
   */
  setActive(device: PresenceDeviceAdmin, active: boolean): void {
    if (this.togglingDeviceId() === device.id) {
      return;
    }
    this.togglingDeviceId.set(device.id);
    this.errorMessage.set(null);
    this.presenceApi.updateDevice(device.id, {
      userId: device.userId,
      name: device.name,
      host: device.host,
      active
    }).subscribe({
      next: () => {
        this.togglingDeviceId.set(null);
        if (this.form.id === device.id) {
          this.form.active = active;
        }
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.togglingDeviceId.set(null);
        this.errorMessage.set(this.messageFrom(error));
      }
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
    if (this.graceMinutes === null || this.graceMinutes < 1 || this.graceMinutes > 1440) {
      this.settingsMessageType.set('error');
      this.settingsMessage.set('Die Karenzzeit muss zwischen 1 und 1440 Minuten liegen.');
      return;
    }
    this.settingsSaving.set(true);
    this.settingsMessage.set(null);
    this.settingsMessageType.set(null);
    this.presenceApi.updateSettings({ awayGraceMinutes: this.graceMinutes }).subscribe({
      next: settings => {
        this.graceMinutes = settings.awayGraceMinutes;
        this.settingsSaving.set(false);
        this.settingsMessageType.set('success');
        this.settingsMessage.set('Gespeichert.');
      },
      error: (error: HttpErrorResponse) => {
        this.settingsSaving.set(false);
        this.settingsMessageType.set('error');
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
